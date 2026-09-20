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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    var keyboardFocused by remember { mutableStateOf(false) }
    val hasFocus = focused || keyboardFocused
    val scale by animateFloatAsState(
        targetValue = if (hasFocus) StravoTokens.FocusScale else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "cardFocusScale",
    )
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .pencilSurface(palette.panel.copy(alpha = 0.88f),
                if (hasFocus) palette.accent else palette.outline, radius,
                focused = hasFocus, dark = palette.isDark)
            .then(
                if (onClick != null) {
                    Modifier.onFocusChanged { keyboardFocused = it.isFocused }.clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

/** Разделитель-карандашная линия. */
@Composable
fun PencilDivider(modifier: Modifier = Modifier, color: Color? = null) {
    val palette = LocalStravoPalette.current
    Box(modifier = modifier.background(color ?: palette.outline.copy(alpha = 0.22f)))
}
