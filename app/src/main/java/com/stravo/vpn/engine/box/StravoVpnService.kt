package com.stravo.vpn.engine.box

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
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
import java.net.InetAddress

/**
 * Единственный VpnService приложения: поднимает TUN и отдаёт его ядру sing-box (libbox).
 *
 * Ядро живёт в этом же процессе: [PlatformInterface] реализован здесь, конфиг узла
 * берётся из защищённого хранилища по обезличенному идентификатору и никуда не логируется.
 * Состояние туннеля читает [SingBoxVpnEngine] через [state].
 */
class StravoVpnService : VpnService(), PlatformInterface {

    private var commandServer: CommandServer? = null
    private var serverThread: Thread? = null
    private var defaultMonitor: DefaultInterfaceMonitor? = null
    private var tunDescriptor: android.os.ParcelFileDescriptor? = null
    private var myInterface: String? = null
    private var currentNodeId: String? = null
    private var failureStep: String? = null

    @Volatile
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        val nodeId = intent?.getStringExtra(EXTRA_NODE_ID)
        val locationId = intent?.getStringExtra(EXTRA_LOCATION_ID) ?: nodeId
        if (nodeId == null) {
            publishError(NODE_MISSING_REASON, locationId)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        trace.record(CoreTrace.STEP_FOREGROUND)
        if (currentNodeId == nodeId && commandServer != null) return START_NOT_STICKY
        startTunnel(nodeId, locationId)
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        // Штатная остановка: следующая диагностика не считает её аварией.
        if (failureStep == null) trace.record(CoreTrace.STEP_IDLE)
        super.onDestroy()
    }

    // --- Запуск и остановка ядра -----------------------------------------

    private val trace: CoreTrace
        get() = (application as StravoApplication).container.coreTrace

    private fun startTunnel(nodeId: String, locationId: String?) {
        currentNodeId = nodeId
        val link = (application as StravoApplication).container.subscriptionImporter.configFor(nodeId)
        if (link == null) {
            publishError(KEY_MISSING_REASON, locationId)
            return
        }
        val config = when (val built = SingBoxConfigBuilder.build(link)) {
            is CoreConfig.Ready -> built.json

            is CoreConfig.Unsupported -> {
                publishError(
                    "Транспорт " + built.transport + " не поддерживается ядром этой сборки",
                    locationId,
                )
                trace.record(CoreTrace.STEP_IDLE)
                return
            }

            CoreConfig.Broken -> {
                publishError(BROKEN_NODE_REASON, locationId)
                trace.record(CoreTrace.STEP_IDLE)
                return
            }
        }

        setState(ConnectionState.Connecting, locationId, null)
        serverThread = Thread({ runCore(config, locationId) }, "stravo-libbox").apply { start() }
    }

    private fun runCore(config: String, locationId: String?) {
        try {
            val options = SetupOptions()
            options.setBasePath(filesDir.absolutePath)
            options.setWorkingPath(filesDir.absolutePath)
            options.setTempPath(cacheDir.absolutePath)
            options.setDebug(false)
            options.setLogMaxLines(200L)
            options.setAppVersion(BuildConfig.VERSION_NAME)
            options.setFixAndroidStack(true)
            Libbox.setup(options)
            trace.record(CoreTrace.STEP_SETUP)

            // Проверяем конфиг до старта: понятная ошибка вместо падения нативного кода.
            Libbox.checkConfig(config)
            trace.record(CoreTrace.STEP_CONFIG)

            val server = Libbox.newCommandServer(handler, this)
            commandServer = server
            trace.record(CoreTrace.STEP_SERVER)
            server.start()
            // OverrideOptions обязателен: ядро разыменовывает его без проверки на null.
            server.startOrReloadService(config, overrideOptions())
            trace.record(CoreTrace.STEP_STARTED)
            setState(ConnectionState.Connected, locationId, System.currentTimeMillis())
        } catch (error: Throwable) {
            // В сообщении нет ни конфига, ни ключей: только текст ошибки ядра.
            val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            failureStep = CoreTrace.errorStep(reason)
            trace.record(failureStep!!)
            publishError("Ядро не подняло туннель: " + reason, locationId)
            stopTunnel()
            stopSelf()
        }
    }

    /** Раздельный туннель из настроек: пустой список — через VPN ходят все приложения. */
    private fun overrideOptions(): OverrideOptions {
        val settings = (application as StravoApplication).container.settings.settings.value
        val options = OverrideOptions()
        options.setAutoRedirect(false)
        val packages = settings.apps.toList()
        if (packages.isEmpty()) return options
        when (settings.appMode) {
            VpnAppMode.ONLY_SELECTED -> options.setIncludePackage(StringList(packages))
            VpnAppMode.EXCEPT_SELECTED -> options.setExcludePackage(StringList(packages))
            VpnAppMode.ALL -> Unit
        }
        return options
    }

    /** Убираем из сообщения ядра адреса и длинные идентификаторы. */
    private fun redact(message: String): String = message
        .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "<id>")
        .replace(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b"), "<ip>")
        .replace(Regex("\\b[0-9a-fA-F]{16,}\\b"), "<key>")
        .take(160)

    private fun stopTunnel() {
        val server = commandServer
        commandServer = null
        currentNodeId = null
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
        setState(ConnectionState.Disconnected, null, null)
        foregroundStarted = false
        stopForegroundCompat()
    }

    private fun setState(state: ConnectionState, locationId: String?, since: Long?) {
        StravoVpnService.publish(
            VpnConnectionSnapshot(state = state, locationId = locationId, connectedSince = since),
        )
        if (state is ConnectionState.Connected) {
            updateNotification(getString(R.string.status_connected))
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
            stopTunnel()
            stopSelf()
        }

        override fun writeDebugMessage(message: String?) {
            // Держим только последнее сообщение и без адресов/ключей: по нему видно,
            // почему туннель не повёз трафик.
            val text = message?.trim().orEmpty()
            if (text.isNotEmpty()) trace.recordCoreMessage(redact(text))
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
        trace.record(CoreTrace.STEP_TUN)
        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(if (options.getMTU() > 0) options.getMTU() else DEFAULT_MTU)
        try {
            builder.setBlocking(options.getStrictRoute())
        } catch (_: Exception) {
        }
        addAddresses(builder, options.getInet4Address())
        addAddresses(builder, options.getInet6Address())
        // Ядро подменяет DNS своим адресом (hijack); если адресов нет — без DNS на TUN
        // Android пойдёт в DNS оператора, который через туннель недоступен.
        val dnsServers = options.getDNSServerAddress().toList().ifEmpty { listOf(FALLBACK_DNS) }
        for (address in dnsServers) {
            try {
                builder.addDnsServer(address)
            } catch (_: IllegalArgumentException) {
            }
        }
        for (packageName in options.getExcludePackage().toList()) {
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }
        }
        for (packageName in options.getIncludePackage().toList()) {
            try {
                builder.addAllowedApplication(packageName)
            } catch (_: Exception) {
            }
        }
        // Своё приложение вне туннеля: подписка и Telegram-ссылки не должны идти через себя же.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }
        for (route in options.getInet4RouteAddress().toList()) {
            try {
                builder.addRoute(route.address(), route.prefix())
            } catch (_: IllegalArgumentException) {
            }
        }
        for (route in options.getInet6RouteAddress().toList()) {
            try {
                builder.addRoute(route.address(), route.prefix())
            } catch (_: IllegalArgumentException) {
            }
        }
        if (options.getAutoRoute()) {
            try {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
            } catch (_: IllegalArgumentException) {
            }
        }
        return try {
            val descriptor = builder.establish() ?: return -1
            // Держим PFD живым: ядро забирает только числовой дескриптор.
            tunDescriptor = descriptor
            descriptor.fd
        } catch (error: Throwable) {
            -1
        }
    }

    private fun addAddresses(builder: Builder, prefixes: RoutePrefixIterator) {
        for (prefix in prefixes.toList()) {
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
        try {
            val enumeration = java.net.NetworkInterface.getNetworkInterfaces()
            while (enumeration != null && enumeration.hasMoreElements()) {
                val source = enumeration.nextElement()
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
                item.setFlags(flagsOf(source))
                item.setType(typeOf(source))
                item.setMetered(false)
                result.add(item)
            }
        } catch (_: Exception) {
        }
        return InterfaceList(result)
    }

    /** Тип интерфейса для ядра: wifi/cellular/ethernet/other — по имени. */
    private fun typeOf(source: java.net.NetworkInterface): Int = when {
        source.name.startsWith("wlan") -> Libbox.InterfaceTypeWIFI
        source.name.startsWith("rmnet") || source.name.startsWith("ccmni") ||
            source.name.startsWith("pdp") -> Libbox.InterfaceTypeCellular
        source.name.startsWith("eth") -> Libbox.InterfaceTypeEthernet
        else -> Libbox.InterfaceTypeOther
    }

    private fun flagsOf(source: java.net.NetworkInterface): Int {
        var flags = 0
        if (source.isUp) flags = flags or FLAG_UP
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
        myInterface = name
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner = ConnectionOwner()

    override fun includeAllNetworks(): Boolean = false

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun usePlatformBridge(): Boolean = false

    override fun usePlatformShell(): Boolean = false

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < 29

    override fun underNetworkExtension(): Boolean = false

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun readWIFIState(): WIFIState? = null

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
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
            .build()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        try {
            stopForeground(true)
        } catch (_: Throwable) {
        }
    }

    /** Минимальный монитор сети по умолчанию: ядру нужны только имя и индекс интерфейса. */
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
            val manager = connectivity ?: return
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
        }

        fun close() {
            try {
                connectivity?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }

        private fun update(network: Network) {
            val manager = connectivity ?: return
            val properties = manager.getLinkProperties(network) ?: return
            val name = properties.interfaceName ?: return
            if (name == myInterface || name.startsWith(TUN_PREFIX)) return
            val index = try {
                java.net.NetworkInterface.getByName(name)?.index ?: 0
            } catch (_: Exception) {
                0
            }
            val capabilities = manager.getNetworkCapabilities(network)
            val expensive = capabilities != null &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            listener.updateDefaultInterface(name, index, expensive, false)
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
        const val ACTION_STOP = "com.stravo.vpn.action.STOP"
        const val EXTRA_NODE_ID = "com.stravo.vpn.extra.NODE_ID"
        const val EXTRA_LOCATION_ID = "com.stravo.vpn.extra.LOCATION_ID"

        private const val SESSION_NAME = "STRAVO VPN"
        private const val TUN_PREFIX = "tun"
        private const val FALLBACK_DNS = "1.1.1.1"
        private const val DEFAULT_MTU = 9000
        private const val CHANNEL_ID = "stravo.vpn.status"
        private const val NOTIFICATION_ID = 1001
        private const val FLAG_UP = 1
        private const val FLAG_LOOPBACK = 4
        private const val FLAG_POINT_TO_POINT = 8
        private const val FLAG_MULTICAST = 16

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

