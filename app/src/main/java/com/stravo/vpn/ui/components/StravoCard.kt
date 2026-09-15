package com.stravo.vpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stravo.vpn.ui.theme.StravoColors
import com.stravo.vpn.ui.theme.StravoShapes

@Composable
fun StravoCard(
    modifier: Modifier = Modifier,
    borderColor: Color = StravoColors.Graphite.copy(alpha = 0.18f),
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = StravoShapes.Card,
        color = StravoColors.PaperLight.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, borderColor),
        elevation = 0.dp,
    ) {
        Box(modifier = Modifier.padding(20.dp), content = content)
    }
}
