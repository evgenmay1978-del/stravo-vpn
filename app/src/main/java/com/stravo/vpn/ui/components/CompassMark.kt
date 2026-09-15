package com.stravo.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.stravo.vpn.ui.theme.StravoColors

@Composable
fun CompassMark(
    modifier: Modifier = Modifier,
    color: Color = StravoColors.Graphite,
) {
    Canvas(modifier = modifier.size(74.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.36f
        drawCircle(color = color.copy(alpha = 0.72f), radius = radius, center = center, style = Stroke(width = 2f))
        drawLine(color, Offset(center.x, center.y - radius - 8f), Offset(center.x, center.y + radius + 8f), strokeWidth = 2f)
        drawLine(color, Offset(center.x - radius - 8f, center.y), Offset(center.x + radius + 8f, center.y), strokeWidth = 2f)
        drawLine(StravoColors.Coral, Offset(center.x, center.y - radius), Offset(center.x + 8f, center.y + 5f), strokeWidth = 4f)
        drawLine(StravoColors.Mint, Offset(center.x, center.y + radius), Offset(center.x - 8f, center.y - 5f), strokeWidth = 4f)
    }
}
