package com.stravo.vpn.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.stravo.vpn.R

/**
 * Фирменный знак S — тот же, что на иконке приложения: каллиграфическая лента
 * с изумрудной нитью. Знак вырезан из эталонной иконки в прозрачный PNG
 * (drawable-nodpi/s_mark.png), поэтому в интерфейсе выглядит ровно как на лаунчере,
 * а не как приблизительный рисунок кодом.
 */
@Composable
fun SMarkImage(
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    Image(
        painter = painterResource(id = R.drawable.s_mark),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        alpha = alpha,
    )
}

/** Упрощённый штрих знака для мелких мест (центр QR, иллюстрация переноса). */
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

    fun terminals(box: Float, origin: Offset): Pair<Offset, Offset> {
        val s = box / 100f
        return Offset(origin.x + 73f * s, origin.y + 27f * s) to
            Offset(origin.x + 27f * s, origin.y + 79f * s)
    }
}

/** Тот же силуэт, нарисованный кодом: графитовая лента и изумрудная нить. */
fun DrawScope.drawSMark(
    origin: Offset,
    box: Float,
    strokeColor: Color,
    accentColor: Color,
    accentAlpha: Float = 1f,
    paperColor: Color? = null,
) {
    val path = SMark.path(box, origin)
    val ribbon = box * 0.190f
    drawPath(path, color = strokeColor, style = Stroke(width = ribbon, cap = StrokeCap.Round))
    if (paperColor != null) {
        drawPath(
            path,
            color = paperColor.copy(alpha = 0.90f),
            style = Stroke(width = ribbon * 0.44f, cap = StrokeCap.Round),
        )
    }
    drawPath(
        path,
        color = accentColor.copy(alpha = accentAlpha),
        style = Stroke(width = ribbon * 0.105f, cap = StrokeCap.Round),
    )
    val (head, tail) = SMark.terminals(box, origin)
    val knob = ribbon * 0.58f
    drawCircle(color = strokeColor, radius = knob, center = head)
    drawCircle(color = strokeColor, radius = knob, center = tail)
}

