package com.stravo.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Чертёжные окружности вокруг главного состояния: пунктир, оси, две направляющие дуги. */
@Composable
fun DraftingRings(
    modifier: Modifier = Modifier,
    ink: Color,
    accent: Color,
    accentAlpha: Float,
    baseAlpha: Float = 0.20f,
) {
    val hairline = 1.dp
    Canvas(modifier = modifier) {
        val min = if (size.width < size.height) size.width else size.height
        val center = Offset(size.width / 2f, size.height / 2f)
        val stroke = Stroke(width = hairline.toPx())
        val dashed = Stroke(
            width = hairline.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 11f), 0f),
        )
        drawCircle(ink.copy(alpha = baseAlpha), radius = min * 0.46f, center = center, style = stroke)
        drawCircle(ink.copy(alpha = baseAlpha * 0.8f), radius = min * 0.375f, center = center, style = stroke)
        drawCircle(ink.copy(alpha = baseAlpha * 0.65f), radius = min * 0.295f, center = center, style = dashed)
        drawCircle(accent.copy(alpha = accentAlpha), radius = min * 0.475f, center = center, style = stroke)
        drawLine(
            color = ink.copy(alpha = baseAlpha * 0.7f),
            start = Offset(center.x - min * 0.5f, center.y),
            end = Offset(center.x + min * 0.5f, center.y),
            strokeWidth = hairline.toPx(),
        )
        drawLine(
            color = ink.copy(alpha = baseAlpha * 0.7f),
            start = Offset(center.x, center.y - min * 0.5f),
            end = Offset(center.x, center.y + min * 0.5f),
            strokeWidth = hairline.toPx(),
        )
    }
}
