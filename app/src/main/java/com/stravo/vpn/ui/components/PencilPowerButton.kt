package com.stravo.vpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stravo.vpn.domain.model.VpnConnectionState
import com.stravo.vpn.ui.theme.StravoColors

@Composable
fun PencilPowerButton(
    state: VpnConnectionState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val accent = when (state) {
        VpnConnectionState.Connected -> StravoColors.Mint
        VpnConnectionState.Error -> StravoColors.Coral
        VpnConnectionState.Preparing,
        VpnConnectionState.Disconnecting -> StravoColors.Sun
        VpnConnectionState.Idle -> StravoColors.Mint
    }
    val label = when (state) {
        VpnConnectionState.Connected -> "ОТКЛЮЧИТЬ"
        VpnConnectionState.Preparing -> "ПОДГОТОВКА"
        VpnConnectionState.Disconnecting -> "ОСТАНОВКА"
        VpnConnectionState.Error -> "ПОВТОРИТЬ"
        VpnConnectionState.Idle -> "ВКЛЮЧИТЬ"
    }

    Surface(
        modifier = Modifier
            .size(190.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        shape = CircleShape,
        color = StravoColors.PaperLight.copy(alpha = 0.76f),
        border = BorderStroke(2.dp, accent.copy(alpha = 0.62f)),
        elevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawPencilRing(center, size.minDimension * 0.30f, accent, 5.dp.toPx())
                drawPencilRing(center, size.minDimension * 0.37f, StravoColors.Coral.copy(alpha = 0.55f), 2.dp.toPx())
                drawPencilRing(center, size.minDimension * 0.25f, StravoColors.Sun.copy(alpha = 0.60f), 2.dp.toPx())
                drawCircle(accent, radius = size.minDimension * 0.095f, center = center)
            }
            Text(
                text = label,
                color = StravoColors.Graphite,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
    }
}
