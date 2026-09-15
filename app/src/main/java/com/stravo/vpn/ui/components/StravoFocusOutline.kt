package com.stravo.vpn.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.stravo.vpn.ui.theme.StravoColors

fun Modifier.stravoFocusOutline(
    focused: Boolean,
    color: Color = StravoColors.Mint,
): Modifier = drawWithContent {
    drawContent()
    if (focused) {
        drawRoundRect(
            color = color,
            style = Stroke(
                width = 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 7.dp.toPx())),
            ),
        )
    }
}
