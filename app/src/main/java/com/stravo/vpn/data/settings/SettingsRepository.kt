package com.stravo.vpn.data.settings

import android.content.Context
import com.stravo.vpn.domain.model.NetworkMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Кто ходит через туннель: все приложения, только выбранные или все, кроме выбранных. */
enum class VpnAppMode {
    ALL,
    ONLY_SELECTED,
    EXCEPT_SELECTED,
}

data class StravoSettings(
    val protocol: String = "Авто (рекомендуется)",
    val notifications: Boolean = true,
    val autoConnect: Boolean = false,
    val startOnBoot: Boolean = false,
    val networkCheck: Boolean = true,
    val language: String = "Русский",
    /** Раздельный туннель: режим и выбранные пакеты. */
    val appMode: VpnAppMode = VpnAppMode.ALL,
    val apps: Set<String> = emptySet(),
    val showSystemApps: Boolean = true,
    /**
     * Диагностика: пустить трафик туннеля напрямую, без узла.
     *
     * Нужно, чтобы отличить «не работает туннель» от «не работает узел»: если с этим
     * переключателем сайты открываются, TUN, DNS и маршруты в порядке, а дело в узле.
     */
    val coreDirectMode: Boolean = false,
    val selectedMode: NetworkMode = NetworkMode.NORMAL_VPN,
    /** DNS defaults match the existing core configuration. No subscription data here. */
    val directDns: String = "8.8.8.8",
    val remoteDns: String = "1.1.1.1",
    val preferProfileDns: Boolean = true,
    val ipv6Policy: String = "prefer_ipv4",
    val directDomains: Set<String> = emptySet(),
    val proxyDomains: Set<String> = emptySet(),
    val blockDomains: Set<String> = emptySet(),
    val directIpCidrs: Set<String> = emptySet(),
    val proxyIpCidrs: Set<String> = emptySet(),
    val blockIpCidrs: Set<String> = emptySet(),
    val bypassLan: Boolean = false,
    /** Reconnect only a user-started session; these flags never grant VPN consent. */
    val autoReconnect: Boolean = false,
    val autoSelect: Boolean = false,
    val autoFailover: Boolean = false,
    val probeUrl: String = "https://www.gstatic.com/generate_204",
    val probeTimeoutMs: Int = 5_000,
    /** Opaque local IDs, never subscription URLs or node credentials. */
    val selectedSourceId: String = "",
    val selectedNodeId: String = "",
)

/**
 * Простые настройки на SharedPreferences.
 * Никаких секретов здесь не хранится — только пользовательские переключатели.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())

    val settings: StateFlow<StravoSettings> = _settings.asStateFlow()

    /** Re-read a validated restore. This only publishes data; it never starts the VPN. */
    @Synchronized
    fun reload() {
        _settings.value = read()
    }

    @Synchronized
    fun update(transform: (StravoSettings) -> StravoSettings) {
        val next = transform(_settings.value)
        val saved = prefs.edit()
            .putString(KEY_PROTOCOL, next.protocol)
            .putBoolean(KEY_NOTIFICATIONS, next.notifications)
            .putBoolean(KEY_AUTO_CONNECT, next.autoConnect)
            .putBoolean(KEY_START_ON_BOOT, next.startOnBoot)
            .putBoolean(KEY_NETWORK_CHECK, next.networkCheck)
            .putString(KEY_LANGUAGE, next.language)
            .putString(KEY_APP_MODE, next.appMode.name)
            .putString(KEY_APPS, next.apps.joinToString(","))
            .putBoolean(KEY_SHOW_SYSTEM, next.showSystemApps)
            .putBoolean(KEY_CORE_DIRECT, next.coreDirectMode)
            .putString(KEY_SELECTED_MODE, next.selectedMode.name)
            .putString("direct_dns", next.directDns)
            .putString("remote_dns", next.remoteDns)
            .putBoolean("prefer_profile_dns", next.preferProfileDns)
            .putString("ipv6_policy", next.ipv6Policy)
            .putStringSet("direct_domains", next.directDomains.toSet())
            .putStringSet("proxy_domains", next.proxyDomains.toSet())
            .putStringSet("block_domains", next.blockDomains.toSet())
            .putStringSet("direct_ip_cidrs", next.directIpCidrs.toSet())
            .putStringSet("proxy_ip_cidrs", next.proxyIpCidrs.toSet())
            .putStringSet("block_ip_cidrs", next.blockIpCidrs.toSet())
            .putBoolean("bypass_lan", next.bypassLan)
            .putBoolean("auto_reconnect", next.autoReconnect)
            .putBoolean("auto_select", next.autoSelect)
            .putBoolean("auto_failover", next.autoFailover)
            .putString("probe_url", next.probeUrl)
            .putInt("probe_timeout_ms", next.probeTimeoutMs)
            .putString("selected_source_id", next.selectedSourceId)
            .putString("selected_node_id", next.selectedNodeId)
            .commit()
        check(saved) { "Не удалось сохранить настройки." }
        _settings.value = next
    }

    private fun read(): StravoSettings = StravoSettings(
        protocol = prefs.getString(KEY_PROTOCOL, null) ?: "Авто (рекомендуется)",
        notifications = prefs.getBoolean(KEY_NOTIFICATIONS, true),
        autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, false),
        startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, false),
        networkCheck = prefs.getBoolean(KEY_NETWORK_CHECK, true),
        language = prefs.getString(KEY_LANGUAGE, null) ?: "Русский",
        appMode = runCatching {
            VpnAppMode.valueOf(prefs.getString(KEY_APP_MODE, null) ?: VpnAppMode.ALL.name)
        }.getOrDefault(VpnAppMode.ALL),
        apps = prefs.getString(KEY_APPS, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet(),
        coreDirectMode = prefs.getBoolean(KEY_CORE_DIRECT, false),
        showSystemApps = prefs.getBoolean(KEY_SHOW_SYSTEM, true),
        selectedMode = NetworkMode.entries.firstOrNull {
            it.name == prefs.getString(KEY_SELECTED_MODE, null)
        } ?: NetworkMode.NORMAL_VPN,
        directDns = prefs.getString("direct_dns", null) ?: "8.8.8.8",
        remoteDns = prefs.getString("remote_dns", null) ?: "1.1.1.1",
        preferProfileDns = prefs.getBoolean("prefer_profile_dns", true),
        ipv6Policy = prefs.getString("ipv6_policy", null) ?: "prefer_ipv4",
        directDomains = prefs.getStringSet("direct_domains", null)?.toSet() ?: emptySet(),
        proxyDomains = prefs.getStringSet("proxy_domains", null)?.toSet() ?: emptySet(),
        blockDomains = prefs.getStringSet("block_domains", null)?.toSet() ?: emptySet(),
        directIpCidrs = prefs.getStringSet("direct_ip_cidrs", null)?.toSet() ?: emptySet(),
        proxyIpCidrs = prefs.getStringSet("proxy_ip_cidrs", null)?.toSet() ?: emptySet(),
        blockIpCidrs = prefs.getStringSet("block_ip_cidrs", null)?.toSet() ?: emptySet(),
        bypassLan = prefs.getBoolean("bypass_lan", false),
        autoReconnect = prefs.getBoolean("auto_reconnect", false),
        autoSelect = prefs.getBoolean("auto_select", false),
        autoFailover = prefs.getBoolean("auto_failover", false),
        probeUrl = prefs.getString("probe_url", null) ?: "https://www.gstatic.com/generate_204",
        probeTimeoutMs = prefs.getInt("probe_timeout_ms", 5_000),
        selectedSourceId = prefs.getString("selected_source_id", null) ?: "",
        selectedNodeId = prefs.getString("selected_node_id", null) ?: "",
    )

    private companion object {
        const val PREFS = "stravo.settings"
        const val KEY_PROTOCOL = "protocol"
        const val KEY_NOTIFICATIONS = "notifications"
        // Legacy auto_connect defaulted to true while inert. Never inherit that consent.
        const val KEY_AUTO_CONNECT = "auto_connect_opt_in"
        const val KEY_START_ON_BOOT = "start_on_boot"
        const val KEY_NETWORK_CHECK = "network_check"
        const val KEY_LANGUAGE = "language"
        const val KEY_APP_MODE = "app_mode"
        const val KEY_APPS = "apps"
        const val KEY_SHOW_SYSTEM = "show_system_apps"
        const val KEY_CORE_DIRECT = "core_direct"
        const val KEY_SELECTED_MODE = "selected_network_mode"
    }
}

