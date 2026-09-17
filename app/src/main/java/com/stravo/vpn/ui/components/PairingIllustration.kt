package com.stravo.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoColors

/**
 * Иллюстрация переноса подписки: QR и телефон. Рисуется кодом,
 * поэтому остаётся резкой на любом экране и не тянет за собой картинку-скриншот.
 */
@Composable
fun PairingIllustration(
    qrPayload: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(StravoColors.PaperLight)
                    .padding(10.dp),
            ) {
                QrCodeView(
                    payload = qrPayload,
                    modifier = Modifier.fillMaxSize(),
                    centerMarkFraction = 0.24f,
                )
            }
            Canvas(modifier = Modifier.size(width = 78.dp, height = 132.dp)) {
                val stroke = 2.dp.toPx()
                val radius = 16.dp.toPx()
                drawRoundRect(
                    color = palette.textPrimary.copy(alpha = 0.85f),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = stroke),
                )
                val inset = 5.dp.toPx()
                drawRoundRect(
                    color = palette.panel,
                    topLeft = Offset(inset, inset + 3.dp.toPx()),
                    size = Size(size.width - inset * 2f, size.height - inset * 2f - 12.dp.toPx()),
                    cornerRadius = CornerRadius(radius * 0.7f, radius * 0.7f),
                )
                val box = size.width * 0.46f
                drawSMark(
                    origin = Offset((size.width - box) / 2f, (size.height - box) / 2f - 4.dp.toPx()),
                    box = box,
                    strokeColor = palette.textPrimary,
                    accentColor = palette.accent,
                )
                drawCircle(
                    color = palette.textPrimary.copy(alpha = 0.45f),
                    radius = 2.5.dp.toPx(),
                    center = Offset(size.width / 2f, size.height - 8.dp.toPx()),
                )
            }
        }
    }
}
