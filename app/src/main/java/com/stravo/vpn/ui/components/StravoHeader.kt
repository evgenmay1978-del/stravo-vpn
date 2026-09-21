package com.stravo.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.stravo.vpn.ui.components.PencilIcon as Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stravo.vpn.R
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** Карандашный щит-компас из визуального референса. */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    withRing: Boolean = true,
) {
    val palette = LocalStravoPalette.current
    Box(
        modifier = modifier
            .size(size)
            .then(if (withRing) Modifier.pencilSurface(
                palette.panel.copy(alpha = 0.45f), palette.outline.copy(alpha = 0.3f), size / 2,
            ) else Modifier),
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.atlas_pencil_crest),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(size * 0.035f),
        )
    }
}

/** Шапка экрана: логотип, название и действие. */
@Composable
fun StravoScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onAction: (() -> Unit)? = null,
    actionIconRes: Int = R.drawable.ic_settings,
    actionLabel: String? = null,
) {
    val palette = LocalStravoPalette.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconAction(
                iconRes = R.drawable.ic_back,
                label = stringResource(id = R.string.action_back),
                onClick = onBack,
            )
        } else {
            BrandMark(size = 40.dp)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = StravoTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, style = StravoType.ScreenTitle, color = palette.textPrimary)
            if (subtitle != null) {
                Text(text = subtitle, style = StravoType.Tiny, color = palette.textSecondary)
            }
        }
        if (onAction != null) {
            IconAction(iconRes = actionIconRes, label = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun IconAction(
    iconRes: Int,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    Box(
        modifier = modifier
            .size(StravoTokens.TouchTargetMin)
            .clip(CircleShape)
            .pencilSurface(palette.panel.copy(alpha = 0.75f), palette.outline, 24.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            tint = palette.textPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}
