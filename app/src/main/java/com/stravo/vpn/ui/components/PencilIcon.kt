package com.stravo.vpn.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp

/** Лёгкий второй карандашный контур; исходный символ и его accessibility-описание сохранены. */
@Composable
fun PencilIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Box(modifier, propagateMinConstraints = true) {
        MaterialIcon(painter, null, Modifier.offset(x = (-0.55).dp, y = 0.45.dp), tint.copy(alpha = tint.alpha * 0.23f))
        MaterialIcon(painter, contentDescription, tint = tint)
    }
}
