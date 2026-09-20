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
    val autoConnect: Boolean = true,
    val startOnBoot: Boolean = false,
    val networkCheck: Boolean = true,
    val language: String = "Русский",
    /** Раздельный туннель: режим и выбранные пакеты. */
    val appMode: VpnAppMode = VpnAppMode.ALL,
    val apps: Set<String> = emptySet(),
    /**
     * Диагностика: пустить трафик туннеля напрямую, без узла.
     *
     * Нужно, чтобы отличить «не работает туннель» от «не работает узел»: если с этим
     * переключателем сайты открываются, TUN, DNS и маршруты в порядке, а дело в узле.
     */
    val coreDirectMode: Boolean = false,
    val selectedMode: NetworkMode = NetworkMode.NORMAL_VPN,
)

/**
 * Простые настройки на SharedPreferences.
 * Никаких секретов здесь не хранится — только пользовательские переключатели.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())

    val settings: StateFlow<StravoSettings> = _settings.asStateFlow()

    fun update(transform: (StravoSettings) -> StravoSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putString(KEY_PROTOCOL, next.protocol)
            .putBoolean(KEY_NOTIFICATIONS, next.notifications)
            .putBoolean(KEY_AUTO_CONNECT, next.autoConnect)
            .putBoolean(KEY_START_ON_BOOT, next.startOnBoot)
            .putBoolean(KEY_NETWORK_CHECK, next.networkCheck)
            .putString(KEY_LANGUAGE, next.language)
            .putString(KEY_APP_MODE, next.appMode.name)
            .putString(KEY_APPS, next.apps.joinToString(","))
            .putBoolean(KEY_CORE_DIRECT, next.coreDirectMode)
            .putString(KEY_SELECTED_MODE, next.selectedMode.name)
            .commit()
    }

    private fun read(): StravoSettings = StravoSettings(
        protocol = prefs.getString(KEY_PROTOCOL, null) ?: "Авто (рекомендуется)",
        notifications = prefs.getBoolean(KEY_NOTIFICATIONS, true),
        autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, true),
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
        selectedMode = NetworkMode.entries.firstOrNull {
            it.name == prefs.getString(KEY_SELECTED_MODE, null)
        } ?: NetworkMode.NORMAL_VPN,
    )

    private companion object {
        const val PREFS = "stravo.settings"
        const val KEY_PROTOCOL = "protocol"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_START_ON_BOOT = "start_on_boot"
        const val KEY_NETWORK_CHECK = "network_check"
        const val KEY_LANGUAGE = "language"
        const val KEY_APP_MODE = "app_mode"
        const val KEY_APPS = "apps"
        const val KEY_CORE_DIRECT = "core_direct"
        const val KEY_SELECTED_MODE = "selected_network_mode"
    }
}

