package com.stravo.vpn.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

fun DrawScope.drawPencilRing(
    center: Offset,
    radius: Float,
    color: Color,
    strokeWidth: Float,
) {
    drawCircle(
        color = color.copy(alpha = 0.86f),
        radius = radius,
        center = center,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
    drawCircle(
        color = color.copy(alpha = 0.32f),
        radius = radius + strokeWidth * 0.9f,
        center = center + Offset(2f, -1f),
        style = Stroke(width = strokeWidth * 0.42f, cap = StrokeCap.Round),
    )
}
