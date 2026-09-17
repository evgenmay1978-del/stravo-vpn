package com.stravo.vpn.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

enum class PencilButtonStyle { Primary, Secondary, Accent }

@Composable
fun PencilButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    style: PencilButtonStyle = PencilButtonStyle.Primary,
    focused: Boolean = false,
    leadingIcon: Painter? = null,
    trailingIcon: Painter? = null,
    radius: Dp = StravoTokens.ButtonRadiusMobile,
    iconTint: androidx.compose.ui.graphics.Color? = null,
) {
    val palette = LocalStravoPalette.current
    val shape = RoundedCornerShape(radius)
    val background = when (style) {
        PencilButtonStyle.Primary -> palette.medallion
        PencilButtonStyle.Secondary -> palette.panel
        PencilButtonStyle.Accent -> palette.accent
    }
    val contentColor = when (style) {
        PencilButtonStyle.Primary -> palette.onAccent
        PencilButtonStyle.Secondary -> palette.textPrimary
        PencilButtonStyle.Accent -> palette.onAccent
    }
    val borderColor = when (style) {
        PencilButtonStyle.Primary -> palette.accent.copy(alpha = if (focused) 0.9f else 0.35f)
        PencilButtonStyle.Secondary -> if (focused) palette.accent else palette.outline.copy(alpha = 0.35f)
        PencilButtonStyle.Accent -> palette.accent
    }
    val scale by animateFloatAsState(
        targetValue = if (focused) StravoTokens.FocusScale else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "buttonFocusScale",
    )
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(background, shape)
            .border(
                width = if (focused) StravoTokens.FocusBorder else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = StravoTokens.TouchTargetMin)
            .padding(horizontal = StravoTokens.SpaceXl, vertical = StravoTokens.SpaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leadingIcon != null) {
            Icon(
                painter = leadingIcon,
                contentDescription = null,
                tint = iconTint ?: palette.accent,
                modifier = Modifier.size(20.dp),
            )
        }
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(start = if (leadingIcon != null) StravoTokens.SpaceMd else 0.dp),
        ) {
            Text(
                text = text,
                style = StravoType.BodyStrong,
                color = contentColor,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = StravoType.Tiny,
                    color = contentColor.copy(alpha = 0.75f),
                )
            }
        }
        if (trailingIcon != null) {
            Icon(
                painter = trailingIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .padding(start = StravoTokens.SpaceMd)
                    .size(20.dp),
            )
        }
    }
}
