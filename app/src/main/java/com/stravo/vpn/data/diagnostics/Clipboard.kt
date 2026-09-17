package com.stravo.vpn.data.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Буфер обмена: журнал ядра (без адресов и ключей) можно скопировать одной кнопкой
 * и передать текстом — файл в «Загрузках» для этого не обязателен.
 */
class Clipboard(private val context: Context) {

    fun copy(text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText(LABEL, text))
    }

    private companion object {
        const val LABEL = "STRAVO core log"
    }
}
