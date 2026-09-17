package com.stravo.vpn.data.diagnostics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Выгружает журнал ядра файлом в «Загрузки», чтобы его можно было прочитать
 * с телефона или передать разработчику: системный logcat чужому uid недоступен.
 *
 * Секретов в журнале нет по построению: адреса и ключи вырезаны в [CoreTrace].
 */
class CoreLogExporter(private val context: Context) {

    /** Пишет журнал в общий файл в «Загрузках» и возвращает его имя. */
    fun export(lines: List<String>): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return exportToAppDir(lines)
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            }
            val existing = findExisting(collection)
            val uri = if (existing != null) {
                resolver.update(existing, values, null, null)
                existing
            } else {
                resolver.insert(collection, values) ?: return null
            }
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(lines.joinToString("\n").toByteArray())
            } ?: return null
            FILE_NAME
        } catch (_: Throwable) {
            exportToAppDir(lines)
        }
    }

    private fun findExisting(collection: Uri): Uri? = try {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = MediaStore.MediaColumns.DISPLAY_NAME + " = ?"
        context.contentResolver.query(collection, projection, selection, arrayOf(FILE_NAME), null)
            ?.use { cursor -> if (cursor.moveToFirst()) Uri.withAppendedPath(collection, cursor.getLong(0).toString()) else null }
    } catch (_: Throwable) {
        null
    }

    /** Запасной путь для старых Android: файл в личном каталоге приложения. */
    private fun exportToAppDir(lines: List<String>): String? = try {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FILE_NAME)
        file.writeText(lines.joinToString("\n"))
        file.absolutePath
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val FILE_NAME = "stravo-core.log"
    }
}
