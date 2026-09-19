package com.stravo.vpn.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stravo.vpn.domain.model.ConnectionState
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoColors
import kotlin.math.cos
import kotlin.math.sin

/** Нативный медальон: гравированный обод, стеклянный диск и подсветка состояния. */
@Composable
fun PowerMedallion(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    size: Dp = 250.dp,
    onClick: (() -> Unit)? = null,
    stateLabel: String? = null,
    contentLabel: String? = null,
) {
    val palette = LocalStravoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, tween(140), label = "powerPress")
    val active = state is ConnectionState.Connected
    val failed = state is ConnectionState.Error
    // На неподвижных экранах нет бесконечного перерисовывания.
    val glow = if (state is ConnectionState.Connecting) {
        val transition = rememberInfiniteTransition(label = "connecting")
        val pulse by transition.animateFloat(
            0.4f, 0.95f,
            infiniteRepeatable(tween(1500), RepeatMode.Reverse),
            label = "connectingGlow",
        )
        pulse
    } else if (active) 0.95f else 0.38f
    val mint = if (failed) StravoColors.Danger else Color(0xFF91DEBD)

    Box(
        modifier = modifier.size(size).scale(scale)
            .semantics {
                if (contentLabel != null) contentDescription = contentLabel
                if (stateLabel != null) stateDescription = stateLabel
            }
            .onFocusChanged { focused = it.isFocused }
            .then(if (onClick != null) Modifier.clickable(
                interactionSource = interaction, indication = null,
                role = Role.Button, onClick = onClick,
            ) else Modifier),
    ) {
        DraftingRings(
            Modifier.matchParentSize().clipToBounds(), palette.textPrimary, palette.accent,
            accentAlpha = if (focused) 0.9f else 0.25f, baseAlpha = 0.22f,
        )
        Canvas(Modifier.matchParentSize().clipToBounds()) {
            val c = center
            val r = this.size.minDimension * 0.367f
            drawCircle(
                Brush.radialGradient(listOf(Color.Black.copy(0.32f), Color.Transparent),
                    center = c + Offset(0f, r * 0.12f), radius = r * 1.28f),
                r * 1.28f, c + Offset(0f, r * 0.12f),
            )
            drawCircle(
                Brush.sweepGradient(listOf(
                    Color(0xFF9A9F90), Color(0xFF253D34), Color(0xFFD9DACE),
                    Color(0xFF435B4E), Color(0xFF172C25), Color(0xFF9A9F90),
                ), c), r * 1.09f, c,
            )
            drawCircle(Color(0xFF223C31), r * 1.035f, c)
            drawCircle(mint.copy(alpha = glow * 0.35f), r * 1.02f, c,
                style = Stroke(r * 0.075f))
            drawCircle(mint.copy(alpha = glow), r * 1.015f, c,
                style = Stroke(1.2.dp.toPx()))
            drawCircle(Color(0xFF0E1B17), r * 0.975f, c)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFF426354), Color(0xFF1D342B), Color(0xFF101E19)),
                    center = c + Offset(-r * 0.35f, -r * 0.48f), radius = r * 1.7f,
                ), r * 0.915f, c,
            )
            drawCircle(Color(0xFFCDD5C5).copy(alpha = 0.52f), r * 0.92f, c,
                style = Stroke(0.7.dp.toPx()))
            drawCircle(mint.copy(alpha = glow * 0.45f), r * 0.82f, c,
                style = Stroke(0.6.dp.toPx()))
            repeat(60) { index ->
                val angle = Math.toRadians(index * 6.0)
                val unit = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(
                    Color(0xFFDFE5D8).copy(alpha = if (index % 5 == 0) 0.55f else 0.22f),
                    c + unit * (r * 1.055f), c + unit * (r * 1.079f),
                    strokeWidth = 0.5.dp.toPx(),
                )
            }
            drawArc(
                Color.White.copy(alpha = 0.28f), 210f, 95f, false,
                Offset(c.x - r * 0.88f, c.y - r * 0.88f),
                Size(r * 1.76f, r * 1.76f), style = Stroke(0.9.dp.toPx()),
            )
            val glyph = r * 0.40f
            val gc = c + Offset(0f, r * 0.03f)
            val glyphColor = if (failed) Color(0xFFE6B2A5) else Color(0xFFB8EBD4)
            for (halo in listOf(true, false)) {
                val color = if (halo) mint.copy(alpha = glow * 0.16f) else glyphColor
                val width = r * if (halo) 0.145f else 0.065f
                drawArc(
                    color, -52f, 284f, false,
                    Offset(gc.x - glyph, gc.y - glyph),
                    Size(glyph * 2f, glyph * 2f), style = Stroke(width, cap = StrokeCap.Round),
                )
                drawLine(
                    color, c + Offset(0f, -r * 0.5f), c + Offset(0f, -r * 0.02f),
                    strokeWidth = width, cap = StrokeCap.Round,
                )
            }
            if (focused) {
                drawCircle(palette.accent, this.size.minDimension * 0.485f, c,
                    style = Stroke(2.dp.toPx()))
                drawCircle(palette.accent.copy(alpha = 0.45f),
                    this.size.minDimension * 0.465f, c, style = Stroke(1.dp.toPx()))
            }
        }
    }
}
