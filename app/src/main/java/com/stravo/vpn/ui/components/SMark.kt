package com.stravo.vpn.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Фирменная буква S рисуется кодом (без bitmap), поэтому одинаково чёткая
 * на любом размере и не превращается в растянутую картинку.
 */
object SMark {

    fun path(box: Float, origin: Offset): Path {
        val s = box / 100f
        fun x(v: Float) = origin.x + v * s
        fun y(v: Float) = origin.y + v * s
        return Path().apply {
            moveTo(x(74f), y(26f))
            cubicTo(x(64f), y(16f), x(40f), y(16f), x(30f), y(30f))
            cubicTo(x(20f), y(44f), x(30f), y(52f), x(48f), y(57f))
            cubicTo(x(68f), y(62f), x(80f), y(68f), x(75f), y(80f))
            cubicTo(x(70f), y(92f), x(44f), y(92f), x(30f), y(80f))
        }
    }
}

/** Карандашный знак: графитовый штрих плюс тонкая изумрудная линия поверх. */
fun DrawScope.drawSMark(
    origin: Offset,
    box: Float,
    strokeColor: Color,
    accentColor: Color,
    accentAlpha: Float = 1f,
) {
    val path = SMark.path(box, origin)
    drawPath(path, color = strokeColor, style = Stroke(width = box * 0.15f, cap = StrokeCap.Round))
    drawPath(
        path,
        color = accentColor.copy(alpha = accentAlpha),
        style = Stroke(width = box * 0.026f, cap = StrokeCap.Round),
    )
}
