package com.stravo.vpn.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.JsonReader
import android.util.JsonToken
import com.stravo.vpn.data.secret.SecretStore
import com.stravo.vpn.platform.DeviceType
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable, password-encrypted backups. Plaintext exists only in memory, never in a temp file.
 *
 * V1 (big endian): magic[8], version[1], KDF[1], iterations[4], salt[16], nonce[12],
 * ciphertext length[4], AES-256-GCM ciphertext + tag[16]. The whole header is authenticated.
 * PBKDF2WithHmacSHA1 is the platform JCA KDF available on ALL supported API 23+ devices;
 * SHA256 starts at API 26. V1 fixes 1,400,000 iterations (no attacker-selected work factor).
 * See developer.android.com/reference/javax/crypto/SecretKeyFactory and
 * cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2.
 *
 * Parent integration: serialize repository mutations/imports around these calls. First validate;
 * only on success clear wanted intent, stop AND await actual tunnel teardown, restore the validated
 * handle on this same instance, then reload repositories. Close the handle if teardown fails.
 * Export does not require stopping VPN. Apply the TV capability policy on reload.
 * These methods never connect or fetch a source.
 * SharedPreferences cannot atomically commit multiple files: preparation is all-or-nothing,
 * ordinary write failures roll back best-effort, but process death/disk failure is not atomic.
 * Password ownership stays with the caller, which must clear its CharArray in finally.
 */
class EncryptedBackup(context: Context, private val secrets: SecretStore) {
    private val appContext = context.applicationContext
    private val stores = STORE_NAMES.associateWith {
        appContext.getSharedPreferences(it, Context.MODE_PRIVATE)
    }

    /** Throws only a generic BackupException (or cancellation), never a secret-bearing cause. */
    suspend fun export(password: CharArray): ByteArray = withContext(Dispatchers.IO) {
        try {
            checkPassword(password)
            val document = synchronized(TRANSACTION_LOCK) {
                secrets.withExclusiveAccess {
                    val preferences = stores.mapValues { (name, prefs) ->
                        portableSettings(name, snapshot(prefs))
                    }
                    encodeDocument(preferences, secrets.exportEntries() ?: throw BackupException())
                }
            }
            val plaintext = document.toByteArray(Charsets.UTF_8)
            try {
                require(plaintext.size <= MAX_PLAINTEXT_BYTES)
                currentCoroutineContext().ensureActive()
                encrypt(plaintext, password)
            } finally {
                plaintext.fill(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw BackupException()
        }
    }

    /** Authenticate and validate entirely in memory, BEFORE stopping VPN or changing any device data. */
    suspend fun validate(payload: ByteArray, password: CharArray): ValidatedBackup? = withContext(Dispatchers.IO) {
        try {
            checkPassword(password)
            val plaintext = decrypt(payload, password)
            val decoded = try {
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext)).toString()
                decodeDocument(text)
            } finally {
                plaintext.fill(0)
            }
            currentCoroutineContext().ensureActive()
            ValidatedBackup(this@EncryptedBackup, decoded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** Parent must await tunnel teardown first. Consumes the handle, also on failure/cancellation. */
    suspend fun restore(validated: ValidatedBackup): Boolean {
        try {
            return withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                // There is no suspension/cancellation point inside the write transaction.
                synchronized(TRANSACTION_LOCK) {
                    val document = validated.take(this@EncryptedBackup) ?: return@synchronized false
                    secrets.withExclusiveAccess { replace(document) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } finally {
            validated.close()
        }
    }

    /** Convenience for callers that already quiesced the tunnel. UI integration uses two phases. */
    suspend fun restore(payload: ByteArray, password: CharArray): Boolean {
        val validated = validate(payload, password) ?: return false
        return restore(validated)
    }

    /**
     * One-use, opaque memory-only handle. Never put it in UI/saved state, a log, a file or a bundle.
     * close() drops references if the parent cannot finish teardown; managed Strings cannot be wiped.
     */
    class ValidatedBackup internal constructor(
        private val owner: EncryptedBackup,
        private var document: Document?,
    ) : Closeable {
        internal fun take(requester: EncryptedBackup): Document? = synchronized(TRANSACTION_LOCK) {
            if (owner !== requester) return@synchronized null
            document.also { document = null }
        }

        override fun close() {
            synchronized(TRANSACTION_LOCK) { document = null }
        }
    }

    private fun replace(document: Document): Boolean {
        val next = document.preferences.mapValues { (name, values) -> portableSettings(name, values) }
        val before = stores.mapValues { (_, prefs) -> snapshot(prefs) }
        val replacement = secrets.prepareReplace(document.secrets) ?: return false
        // Build every editor (including rollback editors) before the first write.
        val editors = stores.mapValues { (name, prefs) -> editor(prefs, next.getValue(name)) }
        val rollback = stores.mapValues { (name, prefs) -> editor(prefs, before.getValue(name)) }
        val attempted = mutableListOf<String>()
        try {
            check(replacement.commit())
            for ((name, edit) in editors) {
                attempted += name // Even commit(false) can replace the in-memory map.
                check(edit.commit())
            }
            return true
        } catch (_: Exception) {
            for (name in attempted.asReversed()) {
                runCatching { rollback.getValue(name).commit() }
            }
            replacement.rollback()
            return false
        }
    }

    private fun portableSettings(name: String, values: Map<String, Any>): Map<String, Any> {
        if (name != SETTINGS) return values
        return values.toMutableMap().apply {
            // Connection consent is local to this installation; a backup never grants it.
            remove("auto_connect")
            put("auto_connect_opt_in", false)
            put("start_on_boot", false)
            put("core_direct", false)
            if (DeviceType.formFactorOf(appContext).isTv) put("selected_network_mode", "NORMAL_VPN")
        }
    }

    private fun snapshot(prefs: SharedPreferences): Map<String, Any> = prefs.all.mapValues { (_, value) ->
        when (value) {
            is Set<*> -> value.map { require(it is String); it }.toSet()
            is String, is Boolean, is Int, is Long, is Float -> value
            else -> throw BackupException()
        }
    }

    private fun editor(prefs: SharedPreferences, values: Map<String, Any>): SharedPreferences.Editor {
        val edit = prefs.edit().clear()
        for ((key, value) in values) {
            when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
                else -> throw BackupException()
            }
        }
        return edit
    }

    internal class Document(val preferences: Map<String, Map<String, Any>>, val secrets: Map<String, String>)

    private fun encodeDocument(preferences: Map<String, Map<String, Any>>, entries: Map<String, String>): String {
        require(entries.size <= MAX_ENTRIES)
        var characters = 0L
        for ((key, value) in entries) characters += key.length.toLong() + value.length
        for (values in preferences.values) {
            for ((key, value) in values) {
                characters += key.length + when (value) {
                    is String -> value.length.toLong()
                    is Set<*> -> value.sumOf { (it as String).length.toLong() }
                    else -> 32L
                }
            }
        }
        require(characters <= MAX_PLAINTEXT_BYTES)
        val root = JSONObject().put("schema", VERSION)
        val encodedPrefs = JSONObject()
        for ((name, values) in preferences) {
            require(values.size <= MAX_ENTRIES)
            val store = JSONObject()
            for ((key, value) in values) {
                checkKey(key)
                validatePreference(name, key, value)
                val type = typeOf(value)
                val encoded = when (value) {
                    is Set<*> -> JSONArray(value.toList().sortedBy { it as String })
                    is Int, is Long, is Float -> value.toString() // Preserve 64-bit integers exactly.
                    else -> value
                }
                store.put(key, JSONObject().put("type", type).put("value", encoded))
            }
            encodedPrefs.put(name, store)
        }
        val encodedSecrets = JSONObject()
        for ((key, value) in entries) {
            checkKey(key)
            checkString(value)
            encodedSecrets.put(key, value)
        }
        val result = root.put("preferences", encodedPrefs).put("secrets", encodedSecrets).toString()
        require(result.length <= MAX_PLAINTEXT_BYTES)
        return result
    }

    private fun decodeDocument(text: String): Document {
        val root = jsonObject(text)
        require(keys(root) == setOf("schema", "preferences", "secrets"))
        require(root.get("schema") == VERSION)
        val preferences = root.getJSONObject("preferences")
        require(keys(preferences) == STORE_NAMES.toSet())
        val decodedPrefs = STORE_NAMES.associateWith { name ->
            val store = preferences.getJSONObject(name)
            require(store.length() <= MAX_ENTRIES)
            keys(store).associateWith { key ->
                checkKey(key)
                val entry = store.getJSONObject(key)
                require(keys(entry) == setOf("type", "value"))
                val raw = entry.get("value")
                val value: Any = when (entry.get("type")) {
                    "string" -> raw as String
                    "boolean" -> raw as Boolean
                    "int" -> (raw as String).toInt()
                    "long" -> (raw as String).toLong()
                    "float" -> (raw as String).toFloat().also { require(it.isFinite()) }
                    "set" -> {
                        val array = raw as JSONArray
                        require(array.length() <= MAX_ENTRIES)
                        (0 until array.length()).map { array.get(it) as String }.toSet().also {
                            require(it.size == array.length())
                        }
                    }
                    else -> throw BackupException()
                }
                validatePreference(name, key, value)
                value
            }
        }
        val entries = root.getJSONObject("secrets")
        require(entries.length() <= MAX_ENTRIES)
        val decodedSecrets = keys(entries).associateWith { key ->
            checkKey(key)
            (entries.get(key) as String).also(::checkString)
        }
        validateSubscriptionState(decodedPrefs[SUBSCRIPTION].orEmpty(), decodedSecrets)
        return Document(decodedPrefs, decodedSecrets)
    }

    /** Mirrors the persisted repository schema BEFORE any preference/secret replacement. */
    private fun validateSubscriptionState(values: Map<String, Any>, entries: Map<String, String>) {
        val raw = values["stravo.subscription.state"] as? String ?: return
        val state = jsonObject(raw)
        require(state.optInt("schema", 0) in 0..2)
        val nodes = state.optJSONArray("nodes") ?: if (state.has("nodes")) throw BackupException() else JSONArray()
        val ids = HashSet<String>()
        val sources = state.optJSONArray("sources")
        if (state.has("sources") || state.optInt("schema", 0) >= 2) require(sources != null)
        val sourceKinds = HashMap<String, String>()
        if (sources != null) for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            val id = source.getString("id")
            checkKey(id)
            require(sourceKinds.put(id, source.getString("service")) == null)
            require(source.getString("service") in setOf("ORDINARY", "CDN"))
            source.getJSONObject("subscription")
            require(source.optInt("updateIntervalHours", 6) in 0..8760)
        }
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            val id = node.getString("id")
            checkKey(id)
            require(ids.add(id))
            val secret = entries[id] ?: throw BackupException()
            require(com.stravo.vpn.domain.subscription.SubscriptionLinkParser.parse(secret)
                is com.stravo.vpn.domain.subscription.ParsedLink.Node)
            if (sources != null) {
                val sourceId = node.getString("sourceId")
                require(sourceKinds[sourceId] == node.getString("service"))
            }
        }
        val catalog = entries["stravo.subscription.sources.v2"]
        if (sources != null) require(catalog != null)
        if (catalog != null) {
            val list = JSONArray(catalog)
            val catalogIds = HashSet<String>()
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val id = item.getString("id")
                checkKey(id)
                require(catalogIds.add(id))
                val service = item.getString("service")
                require(service in setOf("ORDINARY", "CDN"))
                require(sourceKinds[id] == null || sourceKinds[id] == service)
                val url = java.net.URI(item.getString("url"))
                require(url.scheme in setOf("https", "http") && !url.host.isNullOrBlank())
            }
        }
    }

    private fun validatePreference(store: String, key: String, value: Any) {
        when (value) {
            is String -> checkString(value)
            is Float -> require(value.isFinite())
            is Set<*> -> {
                require(value.size <= MAX_ENTRIES)
                value.forEach { checkString(it as String) }
            }
        }
        // Validate known storage types, not evolving domain/source model fields.
        val expected = when (store) {
            SUBSCRIPTION -> if (key == "stravo.subscription.state") "string" else null
            TUNING -> if (key == "variant") "string" else null
            SETTINGS -> when (key) {
                in BOOLEAN_SETTINGS -> "boolean"
                in SET_SETTINGS -> "set"
                "probe_timeout_ms" -> "int"
                in STRING_SETTINGS -> "string"
                else -> null
            }
            else -> throw BackupException()
        }
        if (expected != null) require(typeOf(value) == expected)
        if (store == SUBSCRIPTION && key == "stravo.subscription.state") {
            // Check syntax but preserve the original blob and all future model fields verbatim.
            jsonObject(value as String)
        }
    }

    private fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(VERSION.toByte())
            .put(KDF_ID.toByte()).putInt(ITERATIONS).put(salt).put(nonce)
            .putInt(plaintext.size + TAG_BYTES).array()
        val keyBytes = deriveKey(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plaintext)
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun decrypt(payload: ByteArray, password: CharArray): ByteArray {
        require(payload.size in (HEADER_BYTES + TAG_BYTES + 1)..MAX_FILE_BYTES)
        val input = ByteBuffer.wrap(payload)
        val magic = ByteArray(MAGIC.size).also { input.get(it) }
        require(magic.contentEquals(MAGIC))
        require(input.get().toInt() == VERSION && input.get().toInt() == KDF_ID)
        require(input.int == ITERATIONS)
        val salt = ByteArray(SALT_BYTES).also { input.get(it) }
        val nonce = ByteArray(NONCE_BYTES).also { input.get(it) }
        require(input.int == payload.size - HEADER_BYTES)
        val keyBytes = deriveKey(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(payload, 0, HEADER_BYTES)
            return cipher.doFinal(payload, HEADER_BYTES, payload.size - HEADER_BYTES)
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    class BackupException : Exception("Backup operation failed")

    companion object {
        const val MIN_PASSWORD_LENGTH = 12
        const val MAX_PASSWORD_LENGTH = 256
        private const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
        private const val HEADER_BYTES = 46
        private const val TAG_BYTES = 16
        const val MAX_FILE_BYTES = MAX_PLAINTEXT_BYTES + HEADER_BYTES + TAG_BYTES
        private const val MAX_ENTRIES = 10_000
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val VERSION = 1
        private const val KDF_ID = 1
        private const val ITERATIONS = 1_400_000
        private val MAGIC = "STRAVOBK".toByteArray(Charsets.US_ASCII)
        private val TRANSACTION_LOCK = Any()
        private const val SUBSCRIPTION = "stravo.subscription"
        private const val SETTINGS = "stravo.settings"
        private const val TUNING = "stravo.core.tuning"
        // Excludes stravo.tunnel.session, stravo.device, stravo.core.api and all diagnostics/logs.
        private val STORE_NAMES = listOf(SUBSCRIPTION, SETTINGS, TUNING)
        private val BOOLEAN_SETTINGS = setOf(
            "notifications", "auto_connect", "auto_connect_opt_in", "start_on_boot", "network_check",
            "show_system_apps", "core_direct", "bypass_lan", "auto_reconnect", "auto_select", "auto_failover",
            "prefer_profile_dns",
        )
        private val SET_SETTINGS = setOf(
            "direct_domains", "proxy_domains", "block_domains", "direct_ip_cidrs", "proxy_ip_cidrs", "block_ip_cidrs",
        )
        private val STRING_SETTINGS = setOf(
            "protocol", "language", "app_mode", "apps", "selected_network_mode", "direct_dns", "remote_dns",
            "ipv6_policy", "probe_url", "selected_source_id", "selected_node_id",
        )

        /** SAF providers can lie about size; enforce the cap while streaming, before decryption. */
        fun readBounded(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_FILE_BYTES - output.size() + 1))
                if (count < 0) break
                if (count == 0) throw BackupException()
                if (output.size() + count > MAX_FILE_BYTES) throw BackupException()
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun checkPassword(password: CharArray) {
            require(password.size in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH)
        }

        private fun checkKey(key: String) {
            require(key.isNotBlank() && key.length <= 512 && key.none { it.isISOControl() })
        }

        private fun checkString(value: String) { require(value.length <= MAX_PLAINTEXT_BYTES) }

        private fun typeOf(value: Any): String = when (value) {
            is String -> "string"
            is Boolean -> "boolean"
            is Int -> "int"
            is Long -> "long"
            is Float -> "float"
            is Set<*> -> "set"
            else -> throw BackupException()
        }

        private fun keys(value: JSONObject): Set<String> = value.keys().asSequence().toSet()

        private fun jsonObject(text: String): JSONObject {
            require(text.length <= MAX_PLAINTEXT_BYTES)
            // Strict streaming validation bounds nesting BEFORE the recursive JSONObject parser.
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                require(reader.peek() == JsonToken.BEGIN_OBJECT)
                val names = mutableListOf<MutableSet<String>?>()
                while (reader.peek() != JsonToken.END_DOCUMENT) {
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            require(names.size < 32)
                            reader.beginObject()
                            names.add(mutableSetOf())
                        }
                        JsonToken.BEGIN_ARRAY -> {
                            require(names.size < 32)
                            reader.beginArray()
                            names.add(null)
                        }
                        JsonToken.END_OBJECT -> { reader.endObject(); names.removeAt(names.lastIndex) }
                        JsonToken.END_ARRAY -> { reader.endArray(); names.removeAt(names.lastIndex) }
                        JsonToken.NAME -> require(names.last()!!.add(reader.nextName()))
                        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> reader.nextNull()
                        else -> throw BackupException()
                    }
                }
                require(names.isEmpty())
            }
            return JSONObject(text)
        }
    }
}
