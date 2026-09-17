package com.stravo.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stravo.vpn.pairing.PairingQrEncoder
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoColors
import com.stravo.vpn.ui.theme.StravoType

/**
 * QR рисуется модулями на Canvas, а в центре — фирменная S.
 * Никаких скриншотов и растянутых картинок: код чёткий на любом размере.
 */
@Composable
fun QrCodeView(
    payload: String,
    modifier: Modifier = Modifier,
    moduleColor: Color = StravoColors.Graphite,
    backgroundColor: Color = StravoColors.PaperLight,
    centerMarkFraction: Float = 0.22f,
    showCenterMark: Boolean = true,
) {
    val palette = LocalStravoPalette.current
    val grid = remember(payload) { PairingQrEncoder.grid(payload) }
    val failText = "QR недоступен"

    Box(
        modifier = modifier.background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        if (grid == null) {
            Text(text = failText, style = StravoType.Caption, color = palette.textPrimary)
        } else {
            Canvas(modifier = Modifier.matchParentSize().padding(0.dp)) {
                val quiet = PairingQrEncoder.QUIET_ZONE_MODULES
                val totalModules = grid.width + quiet * 2
                val cell = this.size.minDimension / totalModules
                val originX = (this.size.width - cell * totalModules) / 2f
                val originY = (this.size.height - cell * totalModules) / 2f
                val inset = cell * quiet
                for (y in 0 until grid.height) {
                    for (x in 0 until grid.width) {
                        if (!grid.isDark(x, y)) continue
                        drawRect(
                            color = moduleColor,
                            topLeft = Offset(originX + inset + x * cell, originY + inset + y * cell),
                            size = Size(cell + 0.6f, cell + 0.6f),
                        )
                    }
                }
                if (showCenterMark) {
                    val mark = cell * grid.width * centerMarkFraction
                    val centerX = originX + inset + cell * grid.width / 2f
                    val centerY = originY + inset + cell * grid.height / 2f
                    drawRect(
                        color = backgroundColor,
                        topLeft = Offset(centerX - mark / 2f, centerY - mark / 2f),
                        size = Size(mark, mark),
                    )
                    val box = mark * 0.72f
                    drawSMark(
                        origin = Offset(centerX - box / 2f, centerY - box / 2f),
                        box = box,
                        strokeColor = moduleColor,
                        accentColor = StravoColors.Emerald,
                    )
                }
            }
        }
    }
}
