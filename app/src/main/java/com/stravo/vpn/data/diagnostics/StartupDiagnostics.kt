package com.stravo.vpn.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Короткая честная сводка о прошлом запуске: если приложение или ядро завершились аварийно,
 * пользователь видит это на главном экране, а не молчаливый перезапуск.
 *
 * Сообщение показывается один раз и не содержит ни конфигов, ни адресов узлов.
 */
class StartupDiagnostics(context: Context) {

    val message: String? = build(context.applicationContext)

    private fun build(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shownAt = prefs.getLong(KEY_SHOWN, 0L)

        val exit = lastAbnormalExit(context)
        val fresh = exit != null && exit.first > shownAt
        if (fresh) {
            prefs.edit().putLong(KEY_SHOWN, exit!!.first).apply()
        }

        val trace = CoreTrace(context)
        val step = trace.interruptedStep()
        val coreMessage = trace.lastCoreMessage()
        trace.clear()

        val reason = when {
            fresh && step != null -> exit!!.second + " · последний шаг: " + step
            fresh -> exit!!.second
            step != null -> "Прошлый запуск ядра прервался на шаге: " + step
            else -> null
        }
        return when {
            reason != null && coreMessage != null -> reason + " · ядро: " + coreMessage
            reason != null -> reason
            coreMessage != null -> "Последнее сообщение ядра: " + coreMessage
            else -> null
        }
    }

    /** Последнее аварийное завершение процесса (API 30+). */
    private fun lastAbnormalExit(context: Context): Pair<Long, String>? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            for (info in manager.getHistoricalProcessExitReasons(null, 0, 5)) {
                val text = describe(info) ?: continue
                return info.timestamp to text
            }
            null
        } catch (error: Exception) {
            null
        }
    }

    private fun describe(info: ApplicationExitInfo): String? = when (info.reason) {
        ApplicationExitInfo.REASON_CRASH -> "Прошлый запуск завершился аварийно: сбой приложения"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Прошлый запуск завершился аварийно: сбой ядра"
        ApplicationExitInfo.REASON_ANR -> "Прошлый запуск завис и был остановлен системой"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Прошлый запуск остановлен системой: не хватило памяти"
        ApplicationExitInfo.REASON_SIGNALED -> "Прошлый запуск завершён системным сигналом"
        else -> null
    }

    private companion object {
        const val PREFS = "stravo.diagnostics"
        const val KEY_SHOWN = "shown.exit.timestamp"
    }
}

