package com.stravo.vpn.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

fun DrawScope.drawTopographicLines(color: Color) {
    val lineColor = color.copy(alpha = 0.08f)
    val padding = 20f // Отступ от краев
    
    val paths = listOf(
        listOf(Offset(padding, size.height * 0.15f), Offset(size.width * 0.25f, size.height * 0.08f), Offset(size.width * 0.50f, size.height * 0.15f), Offset(size.width * 0.75f, size.height * 0.06f), Offset(size.width - padding, size.height * 0.12f)),
        listOf(Offset(padding, size.height * 0.20f), Offset(size.width * 0.25f, size.height * 0.12f), Offset(size.width * 0.50f, size.height * 0.20f), Offset(size.width * 0.75f, size.height * 0.10f), Offset(size.width - padding, size.height * 0.16f)),
        listOf(Offset(padding, size.height * 0.75f), Offset(size.width * 0.25f, size.height * 0.85f), Offset(size.width * 0.50f, size.height * 0.72f), Offset(size.width * 0.75f, size.height * 0.86f), Offset(size.width - padding, size.height * 0.78f)),
        listOf(Offset(padding, size.height * 0.82f), Offset(size.width * 0.25f, size.height * 0.92f), Offset(size.width * 0.50f, size.height * 0.78f), Offset(size.width * 0.75f, size.height * 0.94f), Offset(size.width - padding, size.height * 0.85f)),
    )

    paths.forEach { points ->
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { point -> lineTo(point.x, point.y) }
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5f))
    }
}
