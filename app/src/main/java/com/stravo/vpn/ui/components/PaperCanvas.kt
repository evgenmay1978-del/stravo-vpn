package com.stravo.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.stravo.vpn.ui.theme.StravoColors

@Composable
fun PaperCanvas(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(StravoColors.Paper),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawTopographicLines(StravoColors.Graphite)
        }
        content()
    }
}
