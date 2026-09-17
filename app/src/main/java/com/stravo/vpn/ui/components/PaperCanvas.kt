package com.stravo.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.stravo.vpn.R
import com.stravo.vpn.ui.theme.LocalStravoPalette
import kotlin.random.Random

/**
 * Бумажный холст приложения: фон, тихая текстура и карандашные направляющие.
 * Весь декор рисуется строго внутри своих границ (matchParentSize + clipToBounds).
 */
@Composable
fun PaperCanvas(
    modifier: Modifier = Modifier,
    showTexture: Boolean = true,
    showContours: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalStravoPalette.current
    Box(modifier = modifier.background(palette.background)) {
        if (showTexture) {
            Image(
                painter = painterResource(id = R.drawable.paper_texture),
                contentDescription = null,
                modifier = Modifier.matchParentSize().clipToBounds(),
                contentScale = ContentScale.Crop,
                alpha = if (palette.isDark) 0.05f else 0.16f,
            )
        }
        if (showContours) {
            ContourSketch(modifier = Modifier.matchParentSize())
        }
        content()
    }
}

/** Горизонтали топокарты и пара чертёжных окружностей. Рисуются кодом, не картинкой. */
@Composable
fun ContourSketch(
    modifier: Modifier = Modifier,
    alpha: Float = 0.10f,
) {
    val palette = LocalStravoPalette.current
    val ink = if (palette.isDark) Color.White else palette.outline
    val hairline = 1.dp
    Canvas(modifier = modifier.clipToBounds()) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val random = Random(20260917)
        val strokeWidth = hairline.toPx()
        repeat(7) { index ->
            val baseY = height * (0.06f + index * 0.145f)
            val path = Path()
            path.moveTo(-width * 0.05f, baseY)
            var x = -width * 0.05f
            var guard = 0
            while (x < width * 1.05f && guard < 40) {
                val nextX = x + width * (0.16f + random.nextFloat() * 0.14f)
                val nextY = baseY + (random.nextFloat() - 0.5f) * height * 0.07f
                path.quadraticBezierTo((x + nextX) / 2f, nextY, nextX, nextY)
                x = nextX
                guard++
            }
            drawPath(path, color = ink.copy(alpha = alpha), style = Stroke(width = strokeWidth))
        }
        drawCircle(
            color = ink.copy(alpha = alpha * 0.9f),
            radius = width * 0.34f,
            center = Offset(width * 0.86f, height * 0.12f),
            style = Stroke(width = strokeWidth),
        )
        drawCircle(
            color = ink.copy(alpha = alpha * 0.7f),
            radius = width * 0.26f,
            center = Offset(width * 0.10f, height * 0.82f),
            style = Stroke(width = strokeWidth),
        )
    }
}
