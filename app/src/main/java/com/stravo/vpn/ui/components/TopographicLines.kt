package com.stravo.vpn.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.DrawScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

fun DrawScope.drawTopographicLines(color: Color) {
    val lineColor = color.copy(alpha = 0.10f)
    val paths = listOf(
        listOf(Offset(0f, size.height * 0.18f), Offset(size.width * 0.22f, size.height * 0.08f), Offset(size.width * 0.47f, size.height * 0.18f), Offset(size.width * 0.76f, size.height * 0.07f), Offset(size.width, size.height * 0.14f)),
        listOf(Offset(0f, size.height * 0.23f), Offset(size.width * 0.22f, size.height * 0.13f), Offset(size.width * 0.47f, size.height * 0.23f), Offset(size.width * 0.76f, size.height * 0.12f), Offset(size.width, size.height * 0.19f)),
        listOf(Offset(0f, size.height * 0.78f), Offset(size.width * 0.23f, size.height * 0.88f), Offset(size.width * 0.48f, size.height * 0.77f), Offset(size.width * 0.79f, size.height * 0.89f), Offset(size.width, size.height * 0.81f)),
        listOf(Offset(0f, size.height * 0.84f), Offset(size.width * 0.23f, size.height * 0.94f), Offset(size.width * 0.48f, size.height * 0.83f), Offset(size.width * 0.79f, size.height * 0.95f), Offset(size.width, size.height * 0.87f)),
    )

    paths.forEach { points ->
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { point -> lineTo(point.x, point.y) }
        }
        drawPath(path, lineColor, style = Stroke(width = 2f))
    }
}
