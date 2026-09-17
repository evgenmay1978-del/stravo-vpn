package com.stravo.vpn.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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

/**
 * Главный «медальон» подключения: графитовый диск, карандашные окружности
 * и нативный символ питания. Изумрудный появляется только у активного состояния.
 */
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

    val transition = rememberInfiniteTransition(label = "medallion")
    val pulse by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1400), repeatMode = RepeatMode.Reverse),
        label = "pulse",
    )

    val active = state is ConnectionState.Connected
    val connecting = state is ConnectionState.Connecting
    val failed = state is ConnectionState.Error
    val accentAlpha = when {
        connecting -> pulse
        active -> 0.9f
        failed -> 0.35f
        else -> 0.16f
    }
    val glyphColor = when {
        active || connecting -> palette.accent
        failed -> palette.accent.copy(alpha = 0.75f)
        else -> palette.textPrimary.copy(alpha = 0.85f)
    }

    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .semantics {
                if (contentLabel != null) contentDescription = contentLabel
                if (stateLabel != null) stateDescription = stateLabel
            }
            .clip(CircleShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        DraftingRings(
            modifier = Modifier.size(size),
            ink = palette.textPrimary,
            accent = palette.accent,
            accentAlpha = accentAlpha * 0.7f,
        )
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val radius = this.size.minDimension * 0.295f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(palette.medallion, palette.medallionDeep),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            drawCircle(
                color = palette.accent.copy(alpha = accentAlpha),
                radius = radius * 0.985f,
                center = center,
                style = Stroke(width = 3.dp.toPx()),
            )
            drawCircle(
                color = palette.textPrimary.copy(alpha = 0.22f),
                radius = radius * 0.86f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )

            val glyphRadius = radius * 0.40f
            val glyphCenter = Offset(center.x, center.y + radius * 0.06f)
            drawArc(
                color = glyphColor,
                startAngle = -62f,
                sweepAngle = 304f,
                useCenter = false,
                topLeft = Offset(glyphCenter.x - glyphRadius, glyphCenter.y - glyphRadius),
                size = Size(glyphRadius * 2f, glyphRadius * 2f),
                style = Stroke(width = radius * 0.075f, cap = StrokeCap.Round),
            )
            drawLine(
                color = glyphColor,
                start = Offset(center.x, center.y - glyphRadius - radius * 0.10f),
                end = Offset(center.x, center.y + glyphRadius * 0.32f),
                strokeWidth = radius * 0.075f,
                cap = StrokeCap.Round,
            )
        }
    }
}
