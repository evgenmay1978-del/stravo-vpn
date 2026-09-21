package com.stravo.vpn.engine.box

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.stravo.vpn.BuildConfig
import com.stravo.vpn.MainActivity
import com.stravo.vpn.R
import com.stravo.vpn.StravoApplication
import com.stravo.vpn.data.diagnostics.CoreTrace
import com.stravo.vpn.data.settings.VpnAppMode
import com.stravo.vpn.domain.engine.VpnConnectionSnapshot
import com.stravo.vpn.domain.model.ConnectionState
import com.stravo.vpn.domain.policy.CapabilityPolicy
import com.stravo.vpn.domain.subscription.SubscriptionService
import com.stravo.vpn.domain.subscription.SubscriptionServiceClassifier
import com.stravo.vpn.platform.DeviceType
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import io.nekohasekai.libbox.NetworkInterface as BoxInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as CoreNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Единственный VpnService приложения: поднимает TUN и отдаёт его ядру sing-box (libbox).
 *
 * Ядро живёт в этом же процессе: [PlatformInterface] реализован здесь, конфиг узла
 * берётся из защищённого хранилища по обезличенному идентификатору и никуда не логируется.
 * Состояние туннеля читает [SingBoxVpnEngine] через [state].
 *
 * Реализация PlatformInterface повторяет эталонный клиент sing-box-for-android
 * (bg/VPNService.kt, bg/PlatformInterfaceWrapper.kt): список интерфейсов собирается из
 * активных сетей ConnectivityManager с флагами IFF_UP|IFF_RUNNING, DNS, шлюзами и типом,
 * а монитор сети по умолчанию сразу отдаёт ядру текущую сеть — без этого ядро не знает,
 * через какой интерфейс выпускать пакеты.
 */
class StravoVpnService : VpnService(), PlatformInterface {

    private var commandServer: CommandServer? = null
    @Volatile private var latestStartId = 0
    private var defaultMonitor: DefaultInterfaceMonitor? = null
    private var tunDescriptor: android.os.ParcelFileDescriptor? = null
    private var myInterface: String? = null
    private var failureStep: String? = null
    private var interfaceCount = 0
    private var healthWorker: ScheduledExecutorService? = null
    private var automaticSelection = false
    private var loadedNodeIds: List<String> = emptyList()

    @Volatile
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            TunnelSession(this).stop()
            coreWorker.execute {
                stopTunnel(clearState = false)
                if (latestStartId == startId) setState(ConnectionState.Disconnected, null, null)
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        if ((application as StravoApplication).container.restoringBackup) {
            coreWorker.execute {
                stopTunnel(clearState = false)
                if (latestStartId == startId) setState(ConnectionState.Disconnected, null, null)
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        val session = TunnelSession(this)
        val resume = intent == null || intent.action == ACTION_RESUME || intent.action == "android.net.VpnService"
        val systemRequest = intent?.action == "android.net.VpnService" ||
            (Build.VERSION.SDK_INT >= 29 && isAlwaysOn)
        if (resume && !session.wanted && !systemRequest) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val container = (application as StravoApplication).container
        val nodeId = if (resume) session.nodeId?.takeIf { id -> container.subscriptions.nodes.value.any { it.id == id } }
            ?: container.subscriptions.nodes.value.firstOrNull { it.sourceId == session.sourceId }?.id
            else intent?.getStringExtra(EXTRA_NODE_ID)
        automaticSelection = if (resume) session.automatic else intent?.getBooleanExtra(EXTRA_AUTOMATIC, false) == true
        val locationId = intent?.getStringExtra(EXTRA_LOCATION_ID) ?: nodeId
        val label = intent?.getStringExtra(EXTRA_LOCATION_LABEL)
        if (nodeId == null) {
            publishError(NODE_MISSING_REASON, locationId)
            stopSelf()
            return START_NOT_STICKY
        }
        if (prepare(this) != null) {
            session.stop()
            publishError("Требуется системное разрешение VPN", locationId)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        // Intent state follows Android's ordered command delivery, not the sender's timing.
        session.begin(nodeId, automaticSelection)
        startInForeground()
        trace.record(CoreTrace.STEP_FOREGROUND)
        coreWorker.execute {
            if (latestStartId != startId) return@execute
            try {
                stopTunnel(clearState = false)
                if (latestStartId != startId) return@execute
                setState(ConnectionState.Connecting, locationId, null)
                startTunnel(nodeId, locationId, label, startId)
            } catch (error: Throwable) {
                val reason = error.message?.takeIf { it.isNotBlank() } ?: "Ошибка запуска ядра"
                failureStep = CoreTrace.errorStep(reason)
                trace.record(failureStep!!)
                stopTunnel(clearState = false)
                if (latestStartId == startId) publishError(reason, locationId)
                stopSelfResult(startId)
            }
        }
        return if ((application as StravoApplication).container.settings.settings.value.autoReconnect)
            START_STICKY else START_NOT_STICKY
    }

    override fun onRevoke() {
        TunnelSession(this).stop()
        coreWorker.execute { stopTunnel(); stopSelf() }
        super.onRevoke()
    }

    override fun onDestroy() {
        latestStartId = 0
        foregroundStarted = false
        stopForegroundCompat()
        coreWorker.execute {
            // Do not overwrite a newer instance's Connecting or the final startup error.
            stopTunnel(clearState = false)
            if (failureStep == null) trace.record(CoreTrace.STEP_IDLE)
        }
        super.onDestroy()
    }

    // --- Запуск и остановка ядра -----------------------------------------

    private val trace: CoreTrace
        get() = (application as StravoApplication).container.coreTrace

    private fun startTunnel(nodeId: String, locationId: String?, label: String?, startId: Int) {
        failureStep = null
        val container = (application as StravoApplication).container
        val link = container.subscriptionImporter.configFor(nodeId)
        // Журнал прошлого запуска ядра: он объясняет, почему трафик не пошёл.
        container.coreLogReader.publish()
        if (link == null) {
            error(KEY_MISSING_REASON)
        }
        val formFactor = DeviceType.formFactorOf(this)
        val service = container.subscriptions.nodes.value.firstOrNull { it.id == nodeId }?.service
            ?: SubscriptionService.UNKNOWN
        if (!CapabilityPolicy.permits(service, formFactor) ||
            (formFactor.isTv && SubscriptionServiceClassifier.classify(link) == SubscriptionService.CDN)) {
            error("Этот узел недоступен на устройстве. На TV нужен обычный VPN.")
        }
        val variant = container.coreTuning.variant()
        trace.record("версия приложения: " + BuildConfig.VERSION_NAME + "; ядро: " + Libbox.version())
        val directMode = container.settings.settings.value.coreDirectMode
        val settings = container.settings.settings.value
        NetworkPolicy.validate(settings)?.let { error(it) }
        val selectedNode = container.subscriptions.nodes.value.firstOrNull { it.id == nodeId }
            ?: error(NODE_MISSING_REASON)
        require(container.subscriptions.sources.value.any { it.id == selectedNode.sourceId && it.enabled }) {
            "Подписка отключена или удалена"
        }
        TunnelSession(this).rememberSource(selectedNode.sourceId)
        val candidates = container.subscriptions.nodes.value.filter {
            it.sourceId == selectedNode.sourceId && it.service == selectedNode.service &&
                CapabilityPolicy.permits(it.service, formFactor)
        }
        loadedNodeIds = candidates.map { it.id }
        trace.record("конфиг ядра: " + variant.label + (if (directMode) ", прямой режим" else ""))
        val config = when (
            val built = SingBoxGroupBuilder.build(
                nodes = candidates,
                selectedId = nodeId,
                automatic = automaticSelection,
                configFor = container.subscriptionImporter::configFor,
                variant = variant,
                settings = settings,
                apiSecret = container.coreApiToken.value,
            )
        ) {
            is CoreConfig.Ready -> built.json

            is CoreConfig.Unsupported -> {
                error("Транспорт " + built.transport + " не поддерживается ядром этой сборки")
            }

            CoreConfig.Broken -> {
                error(BROKEN_NODE_REASON)
            }
        }

        // Запасной конфиг без локального API метрик. Ядро, собранное без тега
        // with_clash_api, отвергает конфиг с блоком experimental.clash_api целиком —
        // вместе с узлом и TUN. Плитки пинга и скорости такого запуска не стоят.
        val withoutMetrics =
            (SingBoxGroupBuilder.build(candidates, nodeId, automaticSelection,
                container.subscriptionImporter::configFor, variant, settings, apiSecret = null)
                as? CoreConfig.Ready)?.json

        // Что именно ушло в ядро — одной строкой без секретов: по ней разбирается
        // отказ узла (например, «405 Method Not Allowed» от CDN). Ту же схему
        // сохраняем как контекст запуска: он переживает вытеснение журнала и попадает
        // в шапку выгружаемого файла.
        val transport = SingBoxConfigBuilder.describe(
            (SingBoxConfigBuilder.build(link, variant, directMode) as? CoreConfig.Ready)?.json ?: config)
        transport?.let { trace.record("транспорт: " + it) }
        trace.recordContext(
            node = selectedNode.service.name + " / " + selectedNode.protocolLabel + " / " + selectedNode.transportLabel,
            core = variant.label + if (directMode) " · прямой режим" else "",
            transport = transport,
        )

        runCore(config, withoutMetrics, locationId, startId)
    }

    private fun runCore(config: String, fallback: String?, locationId: String?, startId: Int) {
        val options = SetupOptions()
        options.setBasePath(filesDir.absolutePath)
        options.setWorkingPath(filesDir.absolutePath)
        options.setTempPath(cacheDir.absolutePath)
        // debug: без него ядро не зовёт writeDebugMessage и диагностика мертва.
        options.setDebug(true)
        options.setLogMaxLines(200L)
        options.setAppVersion(BuildConfig.VERSION_NAME)
        options.setFixAndroidStack(true)
        Libbox.setup(options)
        trace.record(CoreTrace.STEP_SETUP)

        val server = Libbox.newCommandServer(handler, this)
        commandServer = server
        trace.record(CoreTrace.STEP_SERVER)
        server.start()

        var active = config
        try {
            checkAndStart(server, active)
        } catch (error: Throwable) {
            // Старое ядро без API метрик: повторяем без него, не меняя узел.
            if (fallback == null || fallback == active || !isMetricsUnsupported(error)) {
                throw error
            }
            active = fallback
            trace.record(CoreTrace.STEP_NO_METRICS)
            checkAndStart(server, active)
        }
        trace.record(CoreTrace.STEP_STARTED)
        trace.record(
            "ядро запущено, интерфейсов отдано: " + interfaceCount +
                ", свой интерфейс: " + (myInterface ?: "неизвестен"),
        )
        if (latestStartId == startId) {
            setState(ConnectionState.Checking, locationId, System.currentTimeMillis())
            startHealthMonitor(startId, locationId)
            (application as StravoApplication).container.coreLogReader.publish("ядро старт")
        }
    }

    /**
     * Проверка конфига и запуск ядра. Проверка идёт до старта: понятная ошибка
     * приложения вместо падения нативного кода.
     */
    private fun checkAndStart(server: CommandServer, config: String) {
        Libbox.checkConfig(config)
        trace.record(CoreTrace.STEP_CONFIG)
        // OverrideOptions обязателен: ядро разыменовывает его без проверки на null.
        server.startOrReloadService(config, overrideOptions())
    }

    /**
     * Признак того, что эта сборка ядра собрана без локального API метрик: sing-box
     * отвечает «clash api is not included in this build, rebuild with -tags with_clash_api».
     */
    private fun isMetricsUnsupported(error: Throwable): Boolean {
        val text = (error.message ?: "").lowercase()
        return text.contains("clash api") || text.contains("with_clash_api")
    }

    /** Пустой allowlist не должен незаметно превращаться в VPN для всех приложений. */
    private fun overrideOptions(): OverrideOptions {
        val settings = (application as StravoApplication).container.settings.settings.value
        val options = OverrideOptions()
        options.setAutoRedirect(false)
        val packages = settings.apps.filterNot { it == packageName }
        require(settings.appMode != VpnAppMode.ONLY_SELECTED || packages.isNotEmpty()) {
            "Выберите хотя бы одно приложение для туннеля"
        }
        when (settings.appMode) {
            VpnAppMode.ONLY_SELECTED -> options.setIncludePackage(StringList(packages))
            VpnAppMode.EXCEPT_SELECTED -> options.setExcludePackage(StringList(packages))
            VpnAppMode.ALL -> Unit
        }
        return options
    }

    private fun stopTunnel(clearState: Boolean = true) {
        healthWorker?.shutdownNow()
        healthWorker = null
        val server = commandServer
        commandServer = null
        try {
            server?.closeService()
        } catch (_: Throwable) {
        }
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        defaultMonitor?.close()
        defaultMonitor = null
        try {
            tunDescriptor?.close()
        } catch (_: Throwable) {
        }
        tunDescriptor = null
        myInterface = null
        if (clearState) setState(ConnectionState.Disconnected, null, null)
    }

    private fun setState(state: ConnectionState, locationId: String?, since: Long?) {
        StravoVpnService.publish(
            VpnConnectionSnapshot(state = state, locationId = locationId, connectedSince = since),
        )
        updateNotification(getString(when (state) {
            ConnectionState.Connected -> R.string.status_connected
            ConnectionState.Checking -> R.string.status_checking
            ConnectionState.Degraded -> R.string.status_unverified
            ConnectionState.Connecting -> R.string.status_connecting
            else -> R.string.status_disconnected
        }))
    }

    private fun startHealthMonitor(startId: Int, initialNodeId: String?) {
        val container = (application as StravoApplication).container
        val control = CoreControl(container.coreApiToken.value)
        val since = System.currentTimeMillis()
        var failures = 0
        healthWorker = Executors.newSingleThreadScheduledExecutor { Thread(it, "stravo-health") }.apply {
            scheduleWithFixedDelay({
                if (latestStartId != startId || !TunnelSession(this@StravoVpnService).wanted) return@scheduleWithFixedDelay
                val settings = container.settings.settings.value
                var delay = if (settings.coreDirectMode) null else
                    control.delay(target = settings.probeUrl, timeoutMs = settings.probeTimeoutMs)
                failures = if (delay == null) failures + 1 else 0
                if (failures >= 2 && settings.autoFailover && !automaticSelection && !settings.coreDirectMode) {
                    for (candidate in loadedNodeIds) {
                        if (latestStartId != startId || !TunnelSession(this@StravoVpnService).wanted) break
                        val candidateDelay = control.delay(CoreControl.tag(candidate), settings.probeUrl, settings.probeTimeoutMs)
                        if (candidateDelay != null && control.select(candidate)) {
                            delay = candidateDelay
                            failures = 0
                            break
                        }
                    }
                }
                val actualNode = control.selectedNode()?.takeIf { id -> container.subscriptions.nodes.value.any { it.id == id } }
                    ?: initialNodeId
                if (latestStartId == startId && TunnelSession(this@StravoVpnService).wanted) {
                    if (actualNode != null) TunnelSession(this@StravoVpnService).updateNode(actualNode)
                    setState(if (delay != null) ConnectionState.Connected else ConnectionState.Degraded, actualNode, since)
                }
            }, 0, 20, TimeUnit.SECONDS)
        }
    }

    private fun publishError(reason: String, locationId: String?) {
        StravoVpnService.publish(
            VpnConnectionSnapshot(state = ConnectionState.Error(reason), locationId = locationId),
        )
        updateNotification(getString(R.string.status_error))
    }

    private val handler = object : CommandServerHandler {
        override fun serviceReload() {
            // Перезагрузку конфига приложение не запрашивает: ядро стартует один раз на узел.
        }

        override fun serviceStop() {
            coreWorker.execute { stopTunnel(); stopSelf() }
        }

        override fun writeDebugMessage(message: String?) {
            // Держим последние сообщения без адресов и ключей: по ним видно,
            // почему туннель не повёз трафик.
            val text = message?.trim().orEmpty()
            if (text.isNotEmpty()) trace.recordCoreMessage(text)
        }

        override fun connectSSHAgent(): Int = -1

        override fun getSystemProxyStatus(): SystemProxyStatus? = null

        override fun setSystemProxyEnabled(enabled: Boolean) = Unit

        override fun triggerNativeCrash() = Unit
    }

    // --- PlatformInterface: TUN и сеть ------------------------------------

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) {
            // Разрешение VPN не выдано: ядро получит честную ошибку, а не пустой TUN.
            return -1
        }
        val mtu = if (options.getMTU() > 0) options.getMTU() else DEFAULT_MTU
        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        val inet4 = options.getInet4Address().toList()
        val inet6 = options.getInet6Address().toList()
        addAddresses(builder, inet4)
        addAddresses(builder, inet6)
        val autoRoute = options.getAutoRoute()
        // Маршруты читаем заранее: они нужны и в билдер, и в честной строке диагностики.
        val inet4Routes = options.getInet4RouteAddress().toList()
        val inet6Routes = options.getInet6RouteAddress().toList()
        // Сколько маршрутов реально ушло в билдер: пустой список из конфига означает
        // «весь трафик» (0.0.0.0/0 и ::/0), и в журнале это должно быть видно как 2, а не 0.
        var routesAdded = 0
        if (autoRoute) {
            // Ядро подменяет DNS своим адресом (hijack); без адресов на TUN Android
            // пойдёт в DNS оператора, который через туннель недоступен.
            val dnsServers = options.getDNSServerAddress().toList().ifEmpty { listOf(FALLBACK_DNS) }
            for (address in dnsServers) {
                try {
                    builder.addDnsServer(address)
                } catch (_: IllegalArgumentException) {
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Как в эталонном клиенте: маршруты ядра, а если их нет — весь трафик.
                if (inet4Routes.isNotEmpty()) {
                    for (route in inet4Routes) {
                        try {
                            builder.addRoute(route.address(), route.prefix())
                            routesAdded++
                        } catch (_: IllegalArgumentException) {
                        }
                    }
                } else if (inet4.isNotEmpty()) {
                    builder.addRoute("0.0.0.0", 0)
                    routesAdded++
                }
                if (inet6Routes.isNotEmpty()) {
                    for (route in inet6Routes) {
                        try {
                            builder.addRoute(route.address(), route.prefix())
                            routesAdded++
                        } catch (_: IllegalArgumentException) {
                        }
                    }
                } else if (inet6.isNotEmpty()) {
                    builder.addRoute("::", 0)
                    routesAdded++
                }
            } else {
                // До Android 13 excludeRoute недоступен: отдаём весь трафик в туннель.
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
                routesAdded += 2
            }
            // Android запрещает смешивать allow и disallow: список или один, или другой.
            // Своё приложение в туннель не заводим: его запросы (обновление подписки)
            // уходят в собственный туннель и висят, пока туннель не поедет, — а Android
            // не даёт смешивать allow и disallow, поэтому имя вычищается из списка
            // «только выбранные» до добавления.
            val include = options.getIncludePackage().toList().filterNot { it == packageName }
            val exclude = options.getExcludePackage().toList()
            if (include.isNotEmpty()) {
                var allowed = 0
                for (name in include) {
                    try {
                        builder.addAllowedApplication(name)
                        allowed++
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                    }
                }
                check(allowed > 0) { "Выбранные приложения больше не установлены" }
            } else {
                // Своё приложение вне туннеля: подписка и Telegram-ссылки не должны
                // идти через себя же. Исключение добавляется первым: после allow его уже не принять.
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Exception) {
                }
                for (name in exclude) {
                    if (name == packageName) continue
                    try {
                        builder.addDisallowedApplication(name)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return try {
            val descriptor = builder.establish() ?: return -1
            // Держим PFD живым: ядро забирает только числовой дескриптор.
            tunDescriptor = descriptor
            myInterface = tunnelName(descriptor.fd)
            trace.record(
                CoreTrace.STEP_TUN + ": mtu " + mtu +
                    ", адреса " + (inet4 + inet6).size +
                    ", маршрутов " + routesAdded +
                        ", адрес ядра " + options.getDNSServerAddress().toList().joinToString(",") +
                    ", имя " + (myInterface ?: "неизвестно"),
            )
            descriptor.fd
        } catch (error: Throwable) {
            trace.record("TUN не поднялся: " + (error.message ?: error.javaClass.simpleName))
            -1
        }
    }

    /** Имя TUN-интерфейса по дескриптору: ядру оно нужно, чтобы не уйти в себя же. */
    private fun tunnelName(fd: Int): String? = try {
        java.io.File("/proc/self/fd/" + fd).canonicalFile.name.takeIf { it.startsWith("tun") }
    } catch (_: Throwable) {
        null
    }

    private fun addAddresses(builder: Builder, prefixes: List<RoutePrefix>) {
        for (prefix in prefixes) {
            try {
                builder.addAddress(prefix.address(), prefix.prefix())
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val result = ArrayList<BoxInterface>()
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val known = try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val seen = HashSet<String>()
        // Ошибка одной сети не должна обнулять весь список: без интерфейсов ядро
        // не выпустит ни одного пакета.
        for (network in connectivity?.allNetworks.orEmpty()) {
            try {
                val properties = connectivity?.getLinkProperties(network) ?: continue
                val name = properties.interfaceName ?: continue
                if (!seen.add(name)) continue
                val source = known.firstOrNull { it.name == name } ?: continue
                val capabilities = connectivity.getNetworkCapabilities(network)
                result.add(boxInterface(source, properties, capabilities))
            } catch (_: Exception) {
            }
        }
        interfaceCount = result.size
        return InterfaceList(result)
    }

    /** Описание интерфейса для ядра: имя, индекс, MTU, адреса, флаги, DNS и шлюз. */
    private fun boxInterface(
        source: java.net.NetworkInterface,
        properties: LinkProperties,
        capabilities: NetworkCapabilities?,
    ): BoxInterface {
        val item = BoxInterface()
        item.setName(source.name)
        item.setIndex(source.index)
        item.setMTU(
            try {
                source.mtu
            } catch (_: Exception) {
                DEFAULT_MTU
            },
        )
        // Ядро разбирает адреса как netip.Prefix (MustParsePrefix): нужен
        // формат «адрес/длина префикса» и без scope у IPv6, иначе паника.
        val addresses = ArrayList<String>()
        for (interfaceAddress in source.interfaceAddresses) {
            val address = interfaceAddress.address ?: continue
            val host = address.hostAddress?.substringBefore('%') ?: continue
            addresses.add(host + "/" + interfaceAddress.networkPrefixLength.toInt())
        }
        item.setAddresses(StringList(addresses))
        val dns = ArrayList<String>()
        for (address in properties.dnsServers) {
            address.hostAddress?.substringBefore('%')?.let { dns.add(it) }
        }
        item.setDNSServer(StringList(dns))
        val gateways = ArrayList<String>()
        for (route in properties.routes) {
            if (route.destination?.prefixLength != 0) continue
            val gateway = route.gateway ?: continue
            if (gateway.isAnyLocalAddress) continue
            gateway.hostAddress?.substringBefore('%')?.let { gateways.add(it) }
        }
        item.setGateway(StringList(gateways))
        item.setFlags(flagsOf(source, capabilities))
        item.setType(typeOf(source, capabilities))
        val tunnel = myInterface == source.name || source.name.startsWith(TUN_PREFIX)
        item.setMetered(
            !tunnel &&
                capabilities != null &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
        return item
    }

    /** Тип интерфейса для ядра: wifi/cellular/ethernet/other — по возможностям сети. */
    private fun typeOf(
        source: java.net.NetworkInterface,
        capabilities: NetworkCapabilities?,
    ): Int = when {
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> Libbox.InterfaceTypeWIFI
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> Libbox.InterfaceTypeCellular
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> Libbox.InterfaceTypeEthernet
        source.name.startsWith("wlan") -> Libbox.InterfaceTypeWIFI
        source.name.startsWith("rmnet") || source.name.startsWith("ccmni") ||
            source.name.startsWith("pdp") -> Libbox.InterfaceTypeCellular
        source.name.startsWith("eth") -> Libbox.InterfaceTypeEthernet
        else -> Libbox.InterfaceTypeOther
    }

    /** Флаги интерфейса: IFF_UP|IFF_RUNNING у сети с интернетом — иначе ядро её не увидит. */
    private fun flagsOf(
        source: java.net.NetworkInterface,
        capabilities: NetworkCapabilities?,
    ): Int {
        var flags = 0
        if (source.isUp) flags = flags or FLAG_UP
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            flags = flags or FLAG_UP or FLAG_RUNNING
        }
        if (source.isLoopback) flags = flags or FLAG_LOOPBACK
        if (source.isPointToPoint) flags = flags or FLAG_POINT_TO_POINT
        if (source.supportsMulticast()) flags = flags or FLAG_MULTICAST
        return flags
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultMonitor?.close()
        defaultMonitor = DefaultInterfaceMonitor(listener).also { it.start() }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultMonitor?.close()
        defaultMonitor = null
    }

    override fun registerMyInterface(name: String?) {
        if (!name.isNullOrBlank()) myInterface = name
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner {
        val owner = ConnectionOwner()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return owner
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return owner
        try {
            val uid = connectivity.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort),
            )
            if (uid < 0) return owner
            owner.setUserId(uid)
            val packages = packageManager.getPackagesForUid(uid)
            owner.setUserName(packages?.firstOrNull() ?: "")
            owner.setAndroidPackageNames(StringList(packages?.toList().orEmpty()))
        } catch (_: Throwable) {
        }
        return owner
    }

    override fun includeAllNetworks(): Boolean = false

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun usePlatformBridge(): Boolean = false

    override fun usePlatformShell(): Boolean = false

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < 29

    override fun underNetworkExtension(): Boolean = false

    override fun localDNSTransport(): LocalDNSTransport? = null

    /** Состояние Wi-Fi читаем сами: без него ядро не разрешает правила по SSID. */
    override fun readWIFIState(): WIFIState? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Начиная с Android 10 имя сети доступно только с разрешением на геоданные.
            return null
        }
        return try {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val info = manager?.connectionInfo ?: return null
            val ssid = info.ssid?.removeSurrounding("\"") ?: ""
            if (ssid == "<unknown ssid>") WIFIState("", "") else WIFIState(ssid, info.bssid ?: "")
        } catch (_: Throwable) {
            null
        }
    }

    override fun readSystemSSHHostKey(): String? = null

    override fun tailscaleHostname(): String? = null

    override fun lookupSFTPServer(): String? = null

    override fun lookupUser(username: String?): PlatformUser? = null

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        args: StringIterator?,
        env: String?,
        terminal: Int,
        rows: Int,
    ): ShellSession? = null

    override fun createBridge(options: BridgeOptions?): BridgeSession? = null

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun checkPlatformShell() = Unit

    override fun clearDNSCache() = Unit

    override fun sendNotification(notification: CoreNotification?) = Unit

    override fun cancelNotification(identifier: String?, typeID: Int) = Unit

    // --- Уведомление foreground-сервиса -----------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.service_channel_description)
        manager.createNotificationChannel(channel)
    }

    private fun startInForeground() {
        foregroundStarted = true
        val notification = buildNotification(getString(R.string.status_connecting))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (!foregroundStarted) return
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_power, getString(R.string.action_disconnect_service),
                PendingIntent.getService(this, 1,
                    Intent(this, StravoVpnService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        try {
            stopForeground(true)
        } catch (_: Throwable) {
        }
    }

    /**
     * Монитор сети по умолчанию: ядру нужны имя и индекс интерфейса, через который
     * выпускать пакеты. Текущую сеть отдаём сразу, не дожидаясь изменения сети, —
     * иначе ядро стартует без маршрута наружу.
     */
    private inner class DefaultInterfaceMonitor(private val listener: InterfaceUpdateListener) {

        private val connectivity: ConnectivityManager? =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                update(network)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                update(network)
            }

            override fun onLost(network: Network) = Unit
        }

        fun start() {
            val manager = connectivity
            if (manager == null) {
                listener.updateDefaultInterface("", -1, false, false)
                return
            }
            try {
                if (Build.VERSION.SDK_INT >= 24) {
                    manager.registerDefaultNetworkCallback(callback)
                } else {
                    val request = NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    manager.registerNetworkCallback(request, callback)
                }
            } catch (_: Exception) {
            }
            report(manager.activeNetwork)
        }

        fun close() {
            try {
                connectivity?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }

        private fun update(network: Network) = report(network)

        /** Сеть — это наш туннель (VPN-транспорт или имя tun*): для ядра она не маршрут. */
        private fun isTunnel(network: Network, name: String): Boolean {
            if (name.startsWith(TUN_PREFIX)) return true
            val capabilities = try {
                connectivity?.getNetworkCapabilities(network)
            } catch (_: Exception) {
                null
            }
            return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }

        /**
         * Сообщаем ядру сеть по умолчанию. Свойственных Android задержек две: свойства
         * сети могут быть ещё не готовы, а интерфейс — не виден java.net. Поэтому
         * несколько попыток, и только потом честное «сети нет».
         */
        private fun report(network: Network?) {
            val manager = connectivity
            if (network == null || manager == null) {
                listener.updateDefaultInterface("", -1, false, false)
                return
            }
            for (attempt in 0 until REPORT_ATTEMPTS) {
                val name = try {
                    manager.getLinkProperties(network)?.interfaceName
                } catch (_: Exception) {
                    null
                }
                // Свой TUN отдавать нельзя: ядро тут же отвечает «missing default
                // interface» и перестаёт выпускать пакеты (проверено на устройстве).
                if (!name.isNullOrBlank() && name != myInterface && !isTunnel(network, name)) {
                    val index = try {
                        java.net.NetworkInterface.getByName(name)?.index ?: -1
                    } catch (_: Exception) {
                        -1
                    }
                    if (index > 0) {
                        val capabilities = try {
                            manager.getNetworkCapabilities(network)
                        } catch (_: Exception) {
                            null
                        }
                        val expensive = capabilities != null &&
                            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        val constrained = capabilities != null &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED).not()
                        trace.record("сеть по умолчанию: " + name + " (#" + index + ")")
                        listener.updateDefaultInterface(name, index, expensive, constrained)
                        return
                    }
                }
                try {
                    Thread.sleep(REPORT_RETRY_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
            // Свой туннель может прийти как сеть по умолчанию: тогда берём активную
            // сеть, которая туннелем не является, — иначе ядро останется без маршрута.
            val fallback = try {
                manager.activeNetwork
            } catch (_: Exception) {
                null
            }
            if (fallback != null && fallback != network) {
                reportUnderlying(manager, fallback)?.let { return }
            }
            trace.record("сеть по умолчанию не определилась")
            listener.updateDefaultInterface("", -1, false, false)
        }

        /** Сеть, через которую реально выходим наружу: без VPN-транспорта и без tun*. */
        private fun reportUnderlying(manager: ConnectivityManager, network: Network): Unit? {
            val name = try {
                manager.getLinkProperties(network)?.interfaceName
            } catch (_: Exception) {
                null
            }
            if (name.isNullOrBlank() || name == myInterface || isTunnel(network, name)) return null
            val index = try {
                java.net.NetworkInterface.getByName(name)?.index ?: -1
            } catch (_: Exception) {
                -1
            }
            if (index <= 0) return null
            val capabilities = try {
                manager.getNetworkCapabilities(network)
            } catch (_: Exception) {
                null
            }
            val expensive = capabilities != null &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val constrained = capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED).not()
            trace.record("сеть по умолчанию (в обход туннеля): " + name + " (#" + index + ")")
            listener.updateDefaultInterface(name, index, expensive, constrained)
            return Unit
        }

    }

    // --- Списки для gomobile ----------------------------------------------

    private class StringList(private val values: List<String>) : StringIterator {
        private var index = 0

        override fun hasNext(): Boolean = index < values.size

        override fun len(): Int = values.size

        override fun next(): String = values[index++]
    }

    private class InterfaceList(private val values: List<BoxInterface>) : NetworkInterfaceIterator {
        private var index = 0

        override fun hasNext(): Boolean = index < values.size

        override fun next(): BoxInterface = values[index++]
    }

    companion object {
        const val ACTION_START = "com.stravo.vpn.action.START"
        const val ACTION_RESUME = "com.stravo.vpn.action.RESUME"
        const val EXTRA_AUTOMATIC = "com.stravo.vpn.extra.AUTOMATIC"
        const val ACTION_STOP = "com.stravo.vpn.action.STOP"
        const val EXTRA_NODE_ID = "com.stravo.vpn.extra.NODE_ID"
        const val EXTRA_LOCATION_ID = "com.stravo.vpn.extra.LOCATION_ID"

        /** Подпись узла для журнала («Германия · VLESS · TCP · Reality»), без хостов и ключей. */
        const val EXTRA_LOCATION_LABEL = "com.stravo.vpn.extra.LOCATION_LABEL"

        private const val SESSION_NAME = "STRAVO VPN"
        /** Имена TUN-интерфейсов Android: их нельзя отдавать ядру как маршрут наружу. */
        private const val TUN_PREFIX = "tun"
        private const val FALLBACK_DNS = "1.1.1.1"
        private const val DEFAULT_MTU = 1500
        // Shared across Service instances so destroy/start cannot overlap native cores.
        private val coreWorker = Executors.newSingleThreadExecutor { Thread(it, "stravo-libbox") }
        private const val REPORT_ATTEMPTS = 8
        private const val REPORT_RETRY_MS = 120L
        private const val CHANNEL_ID = "stravo.vpn.status"
        private const val NOTIFICATION_ID = 1001
        // Константы Linux (IFF_*): ядро переводит их в net.Flags через link_flags_unix.go.
        // Значения Go net.Flags здесь не подходят — интерфейс не распознаётся как рабочий.
        private const val FLAG_UP = 0x1
        private const val FLAG_LOOPBACK = 0x8
        private const val FLAG_POINT_TO_POINT = 0x10
        private const val FLAG_RUNNING = 0x40
        private const val FLAG_MULTICAST = 0x1000

        const val NODE_MISSING_REASON = "Ядру не передан узел подписки"
        const val KEY_MISSING_REASON = "Ключ узла не найден в защищённом хранилище"
        const val BROKEN_NODE_REASON = "Узел подписки не разобран: соединение не поднято"

        private val mutableState = MutableStateFlow(VpnConnectionSnapshot())

        /** Состояние туннеля для [SingBoxVpnEngine]. Секретов здесь нет. */
        val state: StateFlow<VpnConnectionSnapshot> = mutableState.asStateFlow()

        internal fun publish(snapshot: VpnConnectionSnapshot) {
            mutableState.value = snapshot
        }
    }
}

/** Итераторы gomobile не реализуют kotlin.collections.Iterable: читаем их вручную. */
private fun StringIterator.toList(): List<String> {
    val result = ArrayList<String>()
    while (hasNext()) result.add(next())
    return result
}

private fun RoutePrefixIterator.toList(): List<RoutePrefix> {
    val result = ArrayList<RoutePrefix>()
    while (hasNext()) result.add(next())
    return result
}

private fun java.util.Enumeration<*>.toList(): List<java.net.NetworkInterface> {
    val result = ArrayList<java.net.NetworkInterface>()
    while (hasMoreElements()) {
        val element = nextElement()
        if (element is java.net.NetworkInterface) result.add(element)
    }
    return result
}
