package com.stravo.vpn.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Доли от размера знака: тело ленты, светлая грань, изумрудная нить и утолщения. */
private const val RIBBON = 0.190f
private const val INNER_FACE = 0.44f
private const val ACCENT_LINE = 0.105f
private const val KNOB = 0.58f

/**
 * Фирменная буква S рисуется кодом (без bitmap), поэтому одинаково чёткая
 * на любом размере и не превращается в растянутую картинку.
 *
 * Форма — плотная карандашная лента с круглыми утолщениями на концах
 * и тонкой изумрудной линией по центру, как в макете.
 */
object SMark {

    fun path(box: Float, origin: Offset): Path {
        val s = box / 100f
        fun x(v: Float) = origin.x + v * s
        fun y(v: Float) = origin.y + v * s
        return Path().apply {
            moveTo(x(73f), y(27f))
            cubicTo(x(65f), y(14f), x(37f), y(13f), x(28f), y(28f))
            cubicTo(x(20f), y(42f), x(33f), y(50f), x(50f), y(54f))
            cubicTo(x(68f), y(58f), x(80f), y(67f), x(73f), y(80f))
            cubicTo(x(66f), y(93f), x(36f), y(92f), x(27f), y(79f))
        }
    }

    /** Точки утолщений на концах знака. */
    fun terminals(box: Float, origin: Offset): Pair<Offset, Offset> {
        val s = box / 100f
        return Offset(origin.x + 73f * s, origin.y + 27f * s) to
            Offset(origin.x + 27f * s, origin.y + 79f * s)
    }
}

/**
 * Карандашный знак: графитовая лента, светлая внутренняя грань (если известен фон),
 * изумрудная нить по центру и утолщения на концах.
 */
fun DrawScope.drawSMark(
    origin: Offset,
    box: Float,
    strokeColor: Color,
    accentColor: Color,
    accentAlpha: Float = 1f,
    paperColor: Color? = null,
) {
    val path = SMark.path(box, origin)
    val ribbon = box * RIBBON
    drawPath(path, color = strokeColor, style = Stroke(width = ribbon, cap = StrokeCap.Round))
    if (paperColor != null) {
        drawPath(
            path,
            color = paperColor.copy(alpha = 0.90f),
            style = Stroke(width = ribbon * INNER_FACE, cap = StrokeCap.Round),
        )
    }
    drawPath(
        path,
        color = accentColor.copy(alpha = accentAlpha),
        style = Stroke(width = ribbon * ACCENT_LINE, cap = StrokeCap.Round),
    )
    val (head, tail) = SMark.terminals(box, origin)
    val knob = ribbon * KNOB
    drawCircle(color = strokeColor, radius = knob, center = head)
    drawCircle(color = strokeColor, radius = knob, center = tail)
}

