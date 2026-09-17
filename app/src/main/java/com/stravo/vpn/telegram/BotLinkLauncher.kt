package com.stravo.vpn.telegram

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.stravo.vpn.domain.model.FormFactor

sealed interface BotLaunchResult {
    /** Открылось приложение Telegram. */
    data object Telegram : BotLaunchResult

    /** Открылся браузер по https-ссылке. */
    data object Browser : BotLaunchResult

    /** Не нашлось ни одного обработчика — ссылку нужно показать и скопировать. */
    data class ManualCopy(val url: String) : BotLaunchResult
}

/**
 * Запуск «Быстрого подключения».
 *
 * Mobile: tg:// → https → копирование ссылки.
 * TV: Telegram почти всегда отсутствует, поэтому ссылку показывает сам экран (QR),
 *     а этот вызов используется только для кнопки «Открыть ссылку».
 */
object BotLinkLauncher {

    fun openQuickConnect(context: Context, formFactor: FormFactor): BotLaunchResult =
        openUrl(context, BotLinks.quickConnectInApp(formFactor), BotLinks.quickConnectHttps(formFactor))

    fun openUrl(context: Context, primary: String, fallback: String): BotLaunchResult {
        if (tryStart(context, primary)) return BotLaunchResult.Telegram
        if (tryStart(context, fallback)) return BotLaunchResult.Browser
        return BotLaunchResult.ManualCopy(fallback)
    }

    fun copyToClipboard(context: Context, url: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("STRAVO", url))
    }

    private fun tryStart(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
