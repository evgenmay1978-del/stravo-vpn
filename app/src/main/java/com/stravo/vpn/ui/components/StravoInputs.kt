package com.stravo.vpn.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import com.stravo.vpn.ui.components.PencilIcon as Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stravo.vpn.R
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** Поле поиска страны. */
@Composable
fun StravoSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    val shape = RoundedCornerShape(StravoTokens.ButtonRadiusMobile)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .pencilSurface(palette.panel.copy(alpha = 0.9f), palette.outline, StravoTokens.ButtonRadiusMobile)
            .defaultMinSize(minHeight = StravoTokens.TouchTargetMin)
            .padding(horizontal = StravoTokens.SpaceLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_search),
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = StravoTokens.SpaceMd),
        ) {
            if (value.isEmpty()) {
                Text(text = placeholder, style = StravoType.Body, color = palette.textSecondary)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = StravoType.Body.copy(color = palette.textPrimary),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Переключатель в стиле STRAVO: графитовая дорожка, изумрудный активный цвет. */
@Composable
fun StravoToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    val trackWidth = 44.dp
    val trackHeight = 24.dp
    val knob = 18.dp
    var focused by remember { mutableStateOf(false) }
    val offset by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "toggleOffset",
    )
    Box(
        modifier = modifier
            .size(width = 52.dp, height = StravoTokens.TouchTargetMin)
            .clip(RoundedCornerShape(12.dp))
            .then(if (focused) Modifier.border(2.dp, palette.accent, RoundedCornerShape(12.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(trackWidth, trackHeight).clip(CircleShape)
                .pencilSurface(if (checked) palette.accent else palette.panelSoft,
                    palette.outline, 12.dp, dark = checked),
            contentAlignment = Alignment.CenterStart,
        ) {
        Box(
            modifier = Modifier
                .offset(x = ((trackWidth - knob - 6.dp) * offset))
                .padding(start = 3.dp)
                .size(knob)
                .clip(CircleShape)
                .pencilSurface(palette.panel, palette.outline, 9.dp),
        )
        }
    }
}

/** Строка настройки/профиля: иконка, подписи и управляющий элемент. */
@Composable
fun StravoSettingRow(
    iconRes: Int,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    focused: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val palette = LocalStravoPalette.current
    var keyboardFocused by remember { mutableStateOf(false) }
    val hasFocus = focused || keyboardFocused
    val scale by animateFloatAsState(
        targetValue = if (hasFocus) StravoTokens.FocusScale else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "rowFocusScale",
    )
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (palette.isDark) StravoTokens.CardRadiusTv else StravoTokens.CardRadiusMobile)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(shape)
            .then(
                if (hasFocus) {
                    Modifier.border(StravoTokens.FocusBorder, palette.accent, shape)
                } else {
                    Modifier
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.onFocusChanged { keyboardFocused = it.isFocused }
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .defaultMinSize(minHeight = StravoTokens.TouchTargetMin)
            .padding(horizontal = StravoTokens.SpaceLg, vertical = StravoTokens.SpaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = palette.textPrimary,
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = StravoTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(text = title, style = StravoType.BodyStrong, color = palette.textPrimary)
            if (subtitle != null) {
                Text(text = subtitle, style = StravoType.Caption, color = palette.textSecondary)
            }
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron),
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
