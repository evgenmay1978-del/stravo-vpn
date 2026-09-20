package com.stravo.vpn.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.stravo.vpn.R
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

/** Карандашный медальон: фактурный диск и нативный индикатор состояния. */
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
        Image(
            painterResource(R.drawable.atlas_pencil_medallion), null,
            Modifier.matchParentSize(), contentScale = ContentScale.Fit,
        )
        Canvas(Modifier.matchParentSize().clipToBounds()) {
            val r = this.size.minDimension * 0.39f
            val glyph = r * 0.38f
            val c = center + Offset(0f, r * 0.025f)
            val pigment = if (failed) Color(0xFFE1AD98) else Color(0xFFBCD3A3)
            // Вторые контуры передают штрих карандаша. Текст и состояние не запечены в картинку.
            repeat(3) { pass ->
                val offset = Offset((pass - 1) * 0.65.dp.toPx(), pass * 0.3.dp.toPx())
                val color = if (pass == 0) Color(0xFF131F16).copy(alpha = 0.6f)
                    else pigment.copy(alpha = (0.6f + glow * 0.4f) * if (pass == 1) 0.95f else 0.36f)
                val width = r * if (pass == 0) 0.085f else 0.062f
                drawArc(color, -52f, 284f, false,
                    Offset(c.x - glyph, c.y - glyph) + offset, Size(glyph * 2, glyph * 2),
                    style = Stroke(width, cap = StrokeCap.Square))
                drawLine(color, center + Offset(0f, -r * 0.49f) + offset,
                    center + Offset(0f, -r * 0.03f) + offset, width, cap = StrokeCap.Square)
            }
            if (failed) drawCircle(StravoColors.Danger.copy(alpha = 0.85f),
                r * 1.1f, center, style = Stroke(2.dp.toPx()))
            if (focused) {
                drawCircle(palette.accent, this.size.minDimension * 0.485f, center,
                    style = Stroke(2.dp.toPx()))
                drawCircle(palette.accent.copy(alpha = 0.45f), this.size.minDimension * 0.465f,
                    center, style = Stroke(1.dp.toPx()))
            }
        }
    }
}
