package com.stravo.vpn.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.random.Random

/**
 * Карандаш на бумаге: волокна, короткая штриховка и три линии разного нажима.
 * Геометрия кэшируется по размеру; текст и интерактивные области не затрагиваются.
 */
fun Modifier.pencilSurface(
    fill: Color,
    ink: Color,
    radius: Dp = 16.dp,
    focused: Boolean = false,
    dark: Boolean = false,
): Modifier = drawWithCache {
    val unit = density
    val inset = 2.5f * unit
    val corner = min(radius.toPx(), size.minDimension / 2f)
    val outline = Path().apply {
        addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner)))
    }
    val random = Random(3917)
    val fibers = List((size.width * size.height / (unit * unit * 65f)).toInt().coerceIn(24, 700)) {
        val start = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
        start to start + Offset((3f + random.nextFloat() * 14f) * unit, -(2f + random.nextFloat() * 9f) * unit)
    }
    val strokes = List(3) { pass ->
        val pad = inset + pass * 1.25f * unit
        pencilOutline(size.width, size.height, corner, pad, unit, 73 + pass)
    }
    onDrawBehind {
        clipPath(outline) {
            drawRect(fill)
            fibers.forEachIndexed { index, (start, end) ->
                drawLine(
                    color = if (dark) Color(0xFFECE7D7).copy(alpha = if (index % 4 == 0) 0.19f else 0.065f)
                        else ink.copy(alpha = if (index % 4 == 0) 0.055f else 0.025f),
                    start = start, end = end, strokeWidth = 0.55f * unit, cap = StrokeCap.Round,
                )
            }
        }
        strokes.forEachIndexed { index, path ->
            drawPath(
                path, ink.copy(alpha = if (focused) 0.9f else listOf(0.65f, 0.32f, 0.15f)[index]),
                style = Stroke(width = (if (focused && index == 0) 1.7f else 0.9f - index * 0.2f) * unit),
            )
        }
    }
}

private fun pencilOutline(w: Float, h: Float, r: Float, pad: Float, unit: Float, seed: Int): Path {
    val radius = (r - pad).coerceAtLeast(0f).coerceAtMost((min(w, h) / 2f - pad).coerceAtLeast(0f))
    val left = pad
    val right = (w - pad).coerceAtLeast(left)
    val top = pad
    val bottom = (h - pad).coerceAtLeast(top)
    val noise = Random(seed)
    fun wobble() = (noise.nextFloat() - 0.5f) * 0.8f * unit
    return Path().apply {
        moveTo(left + radius, top)
        for (i in 1..18) lineTo(left + radius + (right - left - 2 * radius) * i / 18, top + wobble())
        quadraticBezierTo(right, top, right, top + radius)
        for (i in 1..10) lineTo(right + wobble(), top + radius + (bottom - top - 2 * radius) * i / 10)
        quadraticBezierTo(right, bottom, right - radius, bottom)
        for (i in 1..18) lineTo(right - radius - (right - left - 2 * radius) * i / 18, bottom + wobble())
        quadraticBezierTo(left, bottom, left, bottom - radius)
        for (i in 1..10) lineTo(left + wobble(), bottom - radius - (bottom - top - 2 * radius) * i / 10)
        quadraticBezierTo(left, top, left + radius, top)
        close()
    }
}
