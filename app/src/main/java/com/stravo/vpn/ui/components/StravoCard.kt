package com.stravo.vpn.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens

/**
 * Карточка STRAVO: светлая панель с тонкой графитовой линией.
 * На TV в фокусе — лёгкое увеличение, двойная карандашная рамка и изумрудный кант.
 */
@Composable
fun StravoCard(
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    onClick: (() -> Unit)? = null,
    radius: Dp = StravoTokens.CardRadiusMobile,
    padding: Dp = StravoTokens.SpaceLg,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalStravoPalette.current
    val shape = RoundedCornerShape(radius)
    val scale by animateFloatAsState(
        targetValue = if (focused) StravoTokens.FocusScale else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "cardFocusScale",
    )
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(palette.panel)
            .border(
                width = if (focused) StravoTokens.FocusBorder else 1.dp,
                color = if (focused) palette.accent else palette.outline.copy(alpha = 0.30f),
                shape = shape,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        if (focused) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(6.dp)
                    .border(1.dp, palette.outline.copy(alpha = 0.55f), RoundedCornerShape(radius - 6.dp)),
            )
        }
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

/** Разделитель-карандашная линия. */
@Composable
fun PencilDivider(modifier: Modifier = Modifier, color: Color = LocalStravoPalette.current.outline.copy(alpha = 0.22f)) {
    Box(modifier = modifier.background(color))
}
