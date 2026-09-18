package com.stravo.vpn.data.diagnostics

import android.content.Context

/**
 * Хлебные крошки ядра: короткий шаг запуска туннеля, который переживает падение процесса.
 *
 * Нужны, чтобы после аварийного завершения приложение могло честно сказать, на каком шаге
 * оно прервалось: системный лог обычному приложению недоступен. Секретов здесь нет —
 * только названия шагов.
 *
 * Отдельно копится журнал ядра ([logLines]): последние сообщения sing-box. Он живёт
 * только в памяти процесса и нужен, чтобы понять, почему туннель поднялся, а трафик
 * не пошёл. На диск и в сеть журнал не уходит; в [recordCoreMessage] сообщение
 * дополнительно обезличивается ([redact]).
 */
class CoreTrace(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val log = ArrayDeque<String>()

    @Synchronized
    fun record(step: String) {
        prefs.edit()
            .putString(KEY_STEP, step)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
        append("шаг", step)
    }

    /** Шаг, на котором прошлый запуск прервался. null — прошлый запуск завершился штатно. */
    fun interruptedStep(): String? =
        prefs.getString(KEY_STEP, null)?.takeIf { it != STEP_IDLE }

    /** Последнее сообщение ядра (уже без адресов и ключей). */
    fun recordCoreMessage(message: String) {
        val text = redact(message).take(MAX_MESSAGE)
        prefs.edit().putString(KEY_MESSAGE, text).apply()
        append("ядро", text)
    }

    fun lastCoreMessage(): String? = prefs.getString(KEY_MESSAGE, null)

    /** Последние строки ядра, свежие в конце. Только в памяти процесса, на диск не пишутся. */
    @Synchronized
    fun logLines(): List<String> = log.toList()

    @Synchronized
    private fun append(tag: String, text: String) {
        val stamp = TIME_FORMAT.format(java.util.Date())
        log.addLast(stamp + "  " + tag + ": " + text.take(MAX_LINE))
        while (log.size > MAX_LINES) log.removeFirst()
    }

    fun clear() {
        prefs.edit().remove(KEY_STEP).remove(KEY_TIME).remove(KEY_MESSAGE).apply()
        synchronized(this) { log.clear() }
    }

    companion object {
        const val STEP_FOREGROUND = "сервис вышел на передний план"
        const val STEP_SETUP = "ядро настроено (Libbox.setup)"
        const val STEP_CONFIG = "конфиг проверен"
        /** Ядро без локального API метрик: конфиг поднят без блока clash_api. */
        const val STEP_NO_METRICS = "ядро без API метрик (with_clash_api): конфиг без пинга и скорости"
        const val STEP_SERVER = "сервер команд создан"
        const val STEP_STARTED = "ядро запущено"
        const val STEP_TUN = "TUN поднят"
        const val STEP_IDLE = "остановлено"

        fun errorStep(message: String?): String =
            "ошибка ядра: " + (message?.take(160)?.takeIf { it.isNotBlank() } ?: "без описания")

        /** Убираем из сообщения ядра адреса, ключи и длинные идентификаторы. */
        private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val IPV4 = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b")
        private val IPV6 = Regex("[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4}){2,7}")
        private val LONG_HEX = Regex("\\b[0-9a-fA-F]{16,}\\b")

        fun redact(message: String): String = message
            .replace(UUID, "<id>")
            .replace(LONG_HEX, "<key>")
            .replace(IPV4, "<ip>")
            .replace(IPV6, "<addr>")

        private val TIME_FORMAT = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        private const val PREFS = "stravo.core.trace"
        private const val KEY_STEP = "step"
        private const val KEY_TIME = "time"
        private const val KEY_MESSAGE = "message"
        // 400 строк: при разборе «туннель поднялся, а трафик не идёт» нужен не только
        // хвост, но и окно в несколько десятков секунд реальной работы приложений.
        private const val MAX_LINES = 400
        private const val MAX_LINE = 400
        private const val MAX_MESSAGE = 400
    }
}
