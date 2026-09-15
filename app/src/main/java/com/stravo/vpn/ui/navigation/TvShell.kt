package com.stravo.vpn.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.ui.components.PaperCanvas
import com.stravo.vpn.ui.components.stravoFocusOutline
import com.stravo.vpn.ui.theme.StravoColors

@Composable
fun TvShell(
    currentRoute: StravoRoute,
    onNavigate: (StravoRoute) -> Unit,
    content: @Composable () -> Unit,
) {
    val routes = listOf(
        StravoRoute.Home,
        StravoRoute.Servers,
        StravoRoute.Import,
        StravoRoute.Settings,
        StravoRoute.Help,
    )

    PaperCanvas {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(34.dp),
            horizontalArrangement = Arrangement.spacedBy(34.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (currentRoute != StravoRoute.Home) {
                    TvBrandHeader()
                }
                Spacer(modifier = Modifier.weight(1f))
                routes.forEach { route ->
                    TvNavItem(
                        route = route,
                        selected = route == currentRoute,
                        onClick = { onNavigate(route) },
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "TV / ОБЫЧНЫЙ VPN",
                    color = StravoColors.GraphiteMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }
}

@Composable
private fun TvBrandHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "STRAVO VPN",
            color = StravoColors.Graphite,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
        )
        Text(
            text = FormFactor.Tv.wireName.uppercase(),
            color = StravoColors.Mint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}

@Composable
private fun TvNavItem(
    route: StravoRoute,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .stravoFocusOutline(focused),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) StravoColors.Mint.copy(alpha = 0.20f) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, StravoColors.Mint) else null,
        elevation = 0.dp,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
            text = routeLabel(route),
            color = StravoColors.Graphite,
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
