package com.stravo.vpn.data.diagnostics

import android.content.Context
import java.security.SecureRandom

/**
 * Ключ доступа к петлевому API ядра (Clash API).
 *
 * API слушает только 127.0.0.1, но петлевой адрес на Android общий для всех
 * приложений: без ключа любая программа на телефоне могла бы читать статистику
 * и список соединений. Ключ генерируется один раз на устройстве, в журнал и в UI
 * не попадает и передаётся ядру вместе с конфигом.
 */
class CoreApiToken(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val value: String
        get() = prefs.getString(KEY, null) ?: generate()

    private fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY, token).apply()
        return token
    }

    private companion object {
        const val PREFS = "stravo.core.api"
        const val KEY = "token"
        const val TOKEN_BYTES = 16
    }
}
