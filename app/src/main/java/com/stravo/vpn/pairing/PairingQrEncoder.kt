package com.stravo.vpn.pairing

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.stravo.vpn.telegram.BotLinks

/** Модульная сетка QR ровно по размеру кода — без «дробных» модулей при отрисовке. */
data class QrGrid(
    val width: Int,
    val height: Int,
    val cells: BooleanArray,
) {
    fun isDark(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && cells[y * width + x]
}

/**
 * Кодирование QR для переноса подписки.
 *
 * В QR попадает ТОЛЬКО короткоживущий одноразовый токен в виде ссылки на бота.
 * Ни URL подписки, ни UUID, ни ключи в код не помещаются.
 */
object PairingQrEncoder {

    /** Тихая зона в модулях (стандарт QR). */
    const val QUIET_ZONE_MODULES: Int = 4

    fun payloadFor(session: PairingSession): String = BotLinks.pairingHttps(session.token)

    fun grid(payload: String): QrGrid? = runCatching {
        val hints = mapOf<EncodeHintType, Any>(EncodeHintType.CHARACTER_SET to "UTF-8")
        val code = Encoder.encode(payload, ErrorCorrectionLevel.H, hints)
        val matrix = code.matrix
        val width = matrix.width
        val height = matrix.height
        val cells = BooleanArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                cells[y * width + x] = matrix.get(x, y).toInt() == 1
            }
        }
        QrGrid(width = width, height = height, cells = cells)
    }.getOrNull()
}
