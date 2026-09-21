package com.stravo.vpn.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.stravo.vpn.BuildConfig
import com.stravo.vpn.MainActivity
import com.stravo.vpn.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

data class UpdatePreferences(
    val autoCheck: Boolean = true,
    val autoDownloadWifi: Boolean = true,
    val allowBeta: Boolean = AppVersion.parse(BuildConfig.VERSION_NAME)?.pre?.isNotEmpty() == true,
)

data class UpdateState(
    val availableVersion: String? = null,
    val readyVersion: String? = null,
    val busy: Boolean = false,
    val progress: Int? = null,
    val message: String = "Обновления ещё не проверялись",
)

/** One instance in AppContainer. Public metadata only; exclude this preference store from backups.
 * Wire AppUpdateJob.schedule after container creation and render AppUpdatePreferences on phone/TV.
 * No payload is loaded/executed here. PackageInstaller remains the final signature validator.
 */
class AppUpdates(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("stravo.app.updates", Context.MODE_PRIVATE)
    private val networkManager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val directory = File(app.cacheDir, "app-updates")
    private val apk = File(directory, "update.apk")
    private val part = File(directory, "update.part")
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutablePreferences = MutableStateFlow(UpdatePreferences(
        prefs.getBoolean("autoCheck", true), prefs.getBoolean("autoDownloadWifi", true),
        prefs.getBoolean("allowBeta", UpdatePreferences().allowBeta),
    ))
    val preferences: StateFlow<UpdatePreferences> = mutablePreferences.asStateFlow()
    private var candidate: Release? = null
    private var ready: Release? = runCatching { Release.decode(JSONObject(prefs.getString("ready", "")!!)) }
        .getOrNull()?.takeIf { it.allowed(preferences.value) && apk.isFile && apk.length() == it.size &&
            requireNotNull(AppVersion.parse(it.version)) > requireNotNull(AppVersion.parse(BuildConfig.VERSION_NAME)) }
    private val mutableState = MutableStateFlow(UpdateState(readyVersion = ready?.version))
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    @Synchronized
    fun updatePreferences(
        autoCheck: Boolean = preferences.value.autoCheck,
        autoDownloadWifi: Boolean = preferences.value.autoDownloadWifi,
        allowBeta: Boolean = preferences.value.allowBeta,
    ) {
        val channelChanged = allowBeta != preferences.value.allowBeta
        prefs.edit().putBoolean("autoCheck", autoCheck).putBoolean("autoDownloadWifi", autoDownloadWifi)
            .putBoolean("allowBeta", allowBeta).apply()
        mutablePreferences.value = UpdatePreferences(autoCheck, autoDownloadWifi, allowBeta)
        if (channelChanged) {
            mutableState.update { it.copy(availableVersion = null, readyVersion = null) }
            runCatching { NotificationManagerCompat.from(app).cancel(NOTICE_ID) }
        }
        AppUpdateJob.schedule(app)
        // Re-select the channel from cached metadata without spending another API request.
        scope.launch { operation(wait = true) { selectCached() } }
    }

    suspend fun check(force: Boolean = false) = operation {
        if (!force && !preferences.value.autoCheck) return@operation
        val now = System.currentTimeMillis()
        val age = now - prefs.getLong("attempt", 0L)
        if (!force && age in 0 until DAY) { selectCached(); return@operation }
        // Persist BEFORE requesting: failures/restarts must not turn six-hour jobs into API bursts.
        require(prefs.edit().putLong("attempt", now).commit())
        mutableState.update { it.copy(message = "Проверяем обновления…") }
        http(FEED, etag = prefs.getString("etag", null)) { connection ->
            when (connection.responseCode) {
                304 -> require(prefs.contains("metadata"))
                200 -> {
                    val body = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(body.size() + count <= MAX_JSON)
                            body.write(buffer, 0, count)
                        }
                    }
                    val json = body.toString("UTF-8")
                    require(JSONArray(json).length() <= 30)
                    val etag = connection.getHeaderField("ETag")?.takeIf { it.length <= 256 && '\n' !in it && '\r' !in it }
                    require(prefs.edit().putString("metadata", json).putString("etag", etag).commit())
                }
                else -> error("Update metadata unavailable")
            }
        }
        selectCached()
    }

    @Synchronized
    private fun selectCached() {
        val raw = prefs.getString("metadata", null) ?: return
        require(raw.length <= MAX_JSON)
        val installed = requireNotNull(AppVersion.parse(BuildConfig.VERSION_NAME))
        val releases = JSONArray(raw)
        require(releases.length() <= 30)
        val newest = (0 until releases.length()).map { releases.getJSONObject(it) }.filter {
            val version = AppVersion.parse(it.optString("tag_name"))
            !it.optBoolean("draft", true) && version != null && version > installed &&
                (preferences.value.allowBeta || (!it.optBoolean("prerelease") && version.pre.isEmpty()))
        }.maxByOrNull { requireNotNull(AppVersion.parse(it.getString("tag_name"))) }
        candidate = null
        candidate = newest?.let { release ->
            val assets = release.getJSONArray("assets")
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk") && it.optString("state") == "uploaded" }
            val asset = apks.singleOrNull { it.optString("name") == "app-release.apk" } ?: apks.single()
            Release.decode(JSONObject().put("version", release.getString("tag_name"))
                .put("url", asset.getString("browser_download_url")).put("size", asset.getLong("size"))
                .put("digest", asset.getString("digest")).put("beta", release.optBoolean("prerelease")))
        }
        ready?.let { saved ->
            if (!saved.allowed(preferences.value) || !apk.isFile || apk.length() != saved.size ||
                requireNotNull(AppVersion.parse(saved.version)) <= installed) {
                ready = null
                prefs.edit().remove("ready").apply()
                runCatching { NotificationManagerCompat.from(app).cancel(NOTICE_ID) }
            }
        }
        mutableState.update { it.copy(availableVersion = candidate?.version, readyVersion = ready?.version,
            message = when { ready != null -> "Обновление готово к установке"
                candidate != null -> "Доступно обновление ${candidate!!.version}"
                else -> "Новых обновлений в выбранном канале нет" }) }
    }

    suspend fun download(automatic: Boolean = false) = operation {
        if (candidate == null) selectCached()
        val release = candidate ?: return@operation
        if (!release.allowed(preferences.value)) return@operation
        val wifi = if (automatic) unmeteredWifi() else null
        if (automatic && (!preferences.value.autoDownloadWifi || wifi == null)) {
            if (ready != release) mutableState.update { it.copy(message = "Для автозагрузки нужен подтверждённый безлимитный Wi-Fi; при неопределённой сети доступна ручная загрузка") }
            return@operation
        }
        if (ready == release && validate(apk, release)) return@operation
        require(directory.isDirectory || directory.mkdirs())
        if (directory.usableSpace <= release.size * 2 + 64 * 1024 * 1024) {
            mutableState.update { it.copy(message = "Недостаточно свободного места для загрузки и установки APK") }
            return@operation
        }
        mutableState.update { it.copy(progress = 0, message = "Скачиваем обновление…") }
        try {
            http(release.url, wifi = wifi) { connection ->
                require(connection.responseCode == 200)
                val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
                require(length == null || length == release.size)
                connection.inputStream.use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (automatic) require(preferences.value.autoDownloadWifi && unmeteredWifi() == wifi)
                        require(release.allowed(preferences.value))
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= release.size && total <= MAX_APK)
                        output.write(buffer, 0, count)
                        mutableState.update { it.copy(progress = (total * 100 / release.size).toInt()) }
                    }
                    require(total == release.size)
                    output.fd.sync()
                } }
            }
            mutableState.update { it.copy(message = "Проверяем файл и подпись…") }
            require(validate(part, release) && release.allowed(preferences.value))
            currentCoroutineContext().ensureActive()
            // Same-directory atomic replacement; an interrupted/invalid download preserves the ready APK.
            synchronized(this@AppUpdates) {
                require(release.allowed(preferences.value))
                Os.rename(part.absolutePath, apk.absolutePath)
                ready = release
                prefs.edit().putString("ready", release.encode().toString()).apply()
                mutableState.update { it.copy(readyVersion = release.version, message = "Обновление готово к установке") }
                notifyReady(release.version)
            }
        } finally { part.delete() }
    }

    /** User button must explain VPN interruption. Permission settings require a second tap on return. */
    fun install(context: Context) {
        scope.launch { operation {
            val release = requireNotNull(ready)
            require(release.allowed(preferences.value))
            if (!validate(apk, release)) {
                ready = null
                prefs.edit().remove("ready").apply()
                mutableState.update { it.copy(readyVersion = null) }
                error("Invalid cached APK")
            }
            withContext(Dispatchers.Main) {
                if ((app as com.stravo.vpn.StravoApplication).container.restoringBackup) {
                    mutableState.update { it.copy(message = "Дождитесь завершения восстановления резервной копии") }
                    return@withContext
                }
                synchronized(this@AppUpdates) {
                    require(release.allowed(preferences.value))
                    if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        mutableState.update { it.copy(message = "Разрешите установку, вернитесь и нажмите «Установить»") }
                    } else {
                        val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", apk)
                        context.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, APK_MIME)
                            .apply { clipData = ClipData.newRawUri("STRAVO update", uri) }
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                        mutableState.update { it.copy(message = "Подтвердите установку Android. VPN остановится; после обновления проверьте подключение.") }
                    }
                }
            }
        } }
    }

    private fun notifyReady(version: String) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val notifications = NotificationManagerCompat.from(app)
            if (!notifications.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= 26) (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel("app_updates", "Обновления STRAVO", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(app, NOTICE_ID, Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notifications.notify(NOTICE_ID, NotificationCompat.Builder(app, "app_updates")
                .setSmallIcon(R.drawable.ic_vpn).setContentTitle("STRAVO $version готово к установке")
                .setContentText("Откройте приложение и подтвердите установку. VPN будет прерван.")
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build())
        } catch (_: Exception) { /* Denied notifications must never prevent the in-app notice/install. */ }
    }

    @Suppress("DEPRECATION")
    private suspend fun validate(file: File, release: Release): Boolean {
        if (!file.isFile || file.length() != release.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (hex(digest.digest()) != release.digest.removePrefix("sha256:").lowercase()) return false
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        val installed = app.packageManager.getPackageInfo(app.packageName, flags)
        fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun certificates(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners else info.signatures).orEmpty()
            .map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
        val current = certificates(installed)
        return app.packageName == PACKAGE && archive.packageName == PACKAGE && version(archive) > version(installed) &&
            AppVersion.parse(archive.versionName.orEmpty()) == AppVersion.parse(release.version) &&
            current.isNotEmpty() && certificates(archive) == current
    }

    private fun unmeteredWifi(): Network? = networkManager.activeNetwork?.takeIf { network ->
        val caps = networkManager.getNetworkCapabilities(network) ?: return@takeIf false
        // Fail closed for VPN/ambiguous transports: a VPN can migrate its underlying network to cellular.
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !networkManager.isActiveNetworkMetered
    }

    private suspend fun <T> http(initial: String, wifi: Network? = null, etag: String? = null,
        consume: suspend (HttpsURLConnection) -> T): T = coroutineScope {
        val active = AtomicReference<HttpsURLConnection?>()
        val started = SystemClock.elapsedRealtime()
        val observer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                while (true) {
                    delay(250)
                    require(SystemClock.elapsedRealtime() - started < if (initial == FEED) 60_000L else 15 * 60_000L)
                    if (wifi != null) require(preferences.value.autoDownloadWifi && unmeteredWifi() == wifi)
                }
            } finally { active.getAndSet(null)?.disconnect() }
        }
        try {
            var url = URL(initial)
            repeat(6) {
                currentCoroutineContext().ensureActive()
                require(url.protocol == "https" && url.userInfo == null && url.port in listOf(-1, 443) && url.ref == null)
                require(if (initial == FEED) url.toString() == FEED else trustedAsset(url))
                val connection = (wifi?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
                active.set(connection)
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.useCaches = false
                    connection.setRequestProperty("User-Agent", "STRAVO-Updater")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    if (initial == FEED) {
                        connection.setRequestProperty("Accept", "application/vnd.github+json")
                        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                        if (etag != null) connection.setRequestProperty("If-None-Match", etag)
                    }
                    currentCoroutineContext().ensureActive()
                    if (wifi != null) require(preferences.value.autoDownloadWifi && unmeteredWifi() == wifi)
                    if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                        require(initial != FEED)
                        url = URL(url, requireNotNull(connection.getHeaderField("Location")))
                    } else return@coroutineScope consume(connection)
                } finally {
                    runCatching { connection.errorStream?.close() }
                    connection.disconnect(); active.compareAndSet(connection, null)
                }
            }
            error("Too many redirects")
        } finally { observer.cancel(); active.getAndSet(null)?.disconnect() }
    }

    private suspend fun operation(wait: Boolean = false, action: suspend () -> Unit) = withContext(Dispatchers.IO) {
        if (wait) mutex.lock() else if (!mutex.tryLock()) return@withContext
        mutableState.update { it.copy(busy = true) }
        try { action() }
        catch (cancelled: CancellationException) {
            mutableState.update { it.copy(message = "Обновление прервано; можно повторить") }
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(message = "Не удалось безопасно завершить обновление. Повторите попытку.") }
        } finally { mutableState.update { it.copy(busy = false, progress = null) }; mutex.unlock() }
    }

    private data class Release(val version: String, val url: String, val size: Long, val digest: String, val beta: Boolean) {
        fun allowed(prefs: UpdatePreferences) = prefs.allowBeta || (!beta && AppVersion.parse(version)?.pre?.isEmpty() == true)
        fun encode() = JSONObject().put("version", version).put("url", url).put("size", size).put("digest", digest).put("beta", beta)
        companion object {
            fun decode(json: JSONObject): Release {
                val version = json.getString("version")
                require(AppVersion.parse(version) != null)
                val url = json.getString("url")
                val parsed = URL(url)
                require(url.length <= 2048 && parsed.protocol == "https" && parsed.host == "github.com" && parsed.port == -1 &&
                    parsed.userInfo == null && parsed.query == null && parsed.ref == null &&
                    url.startsWith("$ASSET_PREFIX${Uri.encode(version)}/") &&
                    Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk").matches(url.removePrefix("$ASSET_PREFIX${Uri.encode(version)}/")))
                val size = json.getLong("size")
                val digest = json.getString("digest")
                require(size in 1..MAX_APK && Regex("sha256:[0-9a-fA-F]{64}").matches(digest))
                return Release(version, url, size, digest, json.optBoolean("beta"))
            }
        }
    }

    companion object {
        private const val PACKAGE = "com.stravo.vpn"
        private const val FEED = "https://api.github.com/repos/evgenmay1978-del/stravo-vpn/releases?per_page=30"
        private const val ASSET_PREFIX = "https://github.com/evgenmay1978-del/stravo-vpn/releases/download/"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val MAX_JSON = 1024 * 1024
        private const val MAX_APK = 512 * 1024 * 1024L
        private const val NOTICE_ID = 7215
        private fun trustedAsset(url: URL) = (url.host == "github.com" && url.toString().startsWith(ASSET_PREFIX)) ||
            url.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** SemVer precedence, including numeric beta identifiers; build metadata does not change order. */
internal data class AppVersion(val core: List<java.math.BigInteger>, val pre: List<String>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        core.zip(other.core).forEach { (a, b) -> a.compareTo(b).takeIf { it != 0 }?.let { return it } }
        if (pre.isEmpty() || other.pre.isEmpty()) return pre.isEmpty().compareTo(other.pre.isEmpty())
        pre.zip(other.pre).forEach { (a, b) ->
            val x = a.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val y = b.takeIf { it.all(Char::isDigit) }?.toBigInteger()
            val result = when { x != null && y != null -> x.compareTo(y); x != null -> -1; y != null -> 1; else -> a.compareTo(b) }
            if (result != 0) return result
        }
        return pre.size.compareTo(other.pre.size)
    }
    companion object {
        fun parse(text: String): AppVersion? {
            if (text.length > 128) return null
            val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
                .matchEntire(text) ?: return null
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (pre.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return AppVersion((1..3).map { match.groupValues[it].toBigInteger() }, pre)
        }
    }
}
