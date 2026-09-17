package com.stravo.vpn.data.diagnostics

import android.content.Context
import java.io.File

/**
 * Читает журнал самого ядра: libbox перенаправляет stderr sing-box в файл
 * CrashReport-*.log (и в каталог crash_reports) внутри filesDir.
 *
 * Нужен потому, что системный logcat чужому uid недоступен, а сообщения ядра,
 * которые тот отдаёт в интерфейс, могут быть пустыми. Строки уходят в [CoreTrace]
 * и видны на главном экране вместе с шагами запуска.
 */
class CoreLogReader(private val context: Context, private val trace: CoreTrace) {

    /** Последние строки журнала ядра: свежие в конце. */
    fun tail(maxLines: Int = MAX_LINES): List<String> {
        val file = newestLog() ?: return emptyList()
        return try {
            val lines = file.readLines()
            lines.takeLast(maxLines)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Складывает хвост журнала в общий след: он переживает перезапуск процесса. */
    fun publish(tag: String = "ядро") {
        for (line in tail()) {
            val text = line.trim()
            if (text.isNotEmpty()) trace.record(tag + " · " + text)
        }
    }

    /** Самый свежий файл журнала: основной или один из повёрнутых. */
    private fun newestLog(): File? {
        val candidates = ArrayList<File>()
        try {
            context.filesDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith(PREFIX)) candidates.add(file)
            }
            File(context.filesDir, ROTATED).listFiles()?.forEach { file ->
                if (file.isFile) candidates.add(file)
            }
        } catch (_: Throwable) {
            return null
        }
        return candidates.filter { it.length() > 0 }.maxByOrNull { it.lastModified() }
    }

    private companion object {
        const val PREFIX = "CrashReport"
        const val ROTATED = "crash_reports"
        const val MAX_LINES = 60
    }
}
