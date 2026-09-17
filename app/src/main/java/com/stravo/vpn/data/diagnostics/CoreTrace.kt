package com.stravo.vpn.data.diagnostics

import android.content.Context

/**
 * Хлебные крошки ядра: короткий шаг запуска туннеля, который переживает падение процесса.
 *
 * Нужны, чтобы после аварийного завершения приложение могло честно сказать, на каком шаге
 * оно прервалось: системный лог обычному приложению недоступен. Секретов здесь нет —
 * только названия шагов.
 */
class CoreTrace(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(step: String) {
        prefs.edit()
            .putString(KEY_STEP, step)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    /** Шаг, на котором прошлый запуск прервался. null — прошлый запуск завершился штатно. */
    fun interruptedStep(): String? =
        prefs.getString(KEY_STEP, null)?.takeIf { it != STEP_IDLE }

    fun clear() {
        prefs.edit().remove(KEY_STEP).remove(KEY_TIME).apply()
    }

    companion object {
        const val STEP_FOREGROUND = "сервис вышел на передний план"
        const val STEP_SETUP = "ядро настроено (Libbox.setup)"
        const val STEP_CONFIG = "конфиг проверен"
        const val STEP_SERVER = "сервер команд создан"
        const val STEP_STARTED = "ядро запущено"
        const val STEP_TUN = "TUN поднят"
        const val STEP_IDLE = "остановлено"

        fun errorStep(message: String?): String =
            "ошибка ядра: " + (message?.take(120)?.takeIf { it.isNotBlank() } ?: "без описания")

        private const val PREFS = "stravo.core.trace"
        private const val KEY_STEP = "step"
        private const val KEY_TIME = "time"
    }
}

