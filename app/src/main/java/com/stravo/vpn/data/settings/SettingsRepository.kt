package com.stravo.vpn.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StravoSettings(
    val protocol: String = "Авто (рекомендуется)",
    val notifications: Boolean = true,
    val autoConnect: Boolean = true,
    val startOnBoot: Boolean = false,
    val networkCheck: Boolean = true,
    val language: String = "Русский",
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
            .apply()
    }

    private fun read(): StravoSettings = StravoSettings(
        protocol = prefs.getString(KEY_PROTOCOL, null) ?: "Авто (рекомендуется)",
        notifications = prefs.getBoolean(KEY_NOTIFICATIONS, true),
        autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, true),
        startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, false),
        networkCheck = prefs.getBoolean(KEY_NETWORK_CHECK, true),
        language = prefs.getString(KEY_LANGUAGE, null) ?: "Русский",
    )

    private companion object {
        const val PREFS = "stravo.settings"
        const val KEY_PROTOCOL = "protocol"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_START_ON_BOOT = "start_on_boot"
        const val KEY_NETWORK_CHECK = "network_check"
        const val KEY_LANGUAGE = "language"
    }
}
