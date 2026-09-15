package com.stravo.vpn.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.domain.model.VpnConnectionState
import com.stravo.vpn.ui.components.CompassMark
import com.stravo.vpn.ui.components.PencilPowerButton
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.screens.home.HomeScreen
import com.stravo.vpn.ui.theme.StravoColors
import com.stravo.vpn.ui.theme.StravoTypography

@Composable
fun StravoNavGraph(formFactor: FormFactor) {
    var routeKey by remember { mutableStateOf(StravoRoute.Home.key) }
    val requestedRoute = routeFromKey(routeKey)
    val currentRoute = if (formFactor == FormFactor.Tv && requestedRoute == StravoRoute.WhiteList) {
        StravoRoute.Home
    } else {
        requestedRoute
    }

    fun navigate(route: StravoRoute) {
        if (formFactor == FormFactor.Tv && route == StravoRoute.WhiteList) {
            routeKey = StravoRoute.Home.key
        } else {
            routeKey = route.key
        }
    }

    val content: @Composable () -> Unit = {
        if (currentRoute == StravoRoute.Home) {
            HomeScreen(formFactor = formFactor, onNavigate = ::navigate)
        } else {
            RoutePlaceholder(route = currentRoute, formFactor = formFactor)
        }
    }

    when (formFactor) {
        FormFactor.Phone -> PhoneShell(currentRoute, ::navigate, content)
        FormFactor.Tv -> TvShell(currentRoute, ::navigate, content)
    }
}

@Composable
private fun RoutePlaceholder(route: StravoRoute, formFactor: FormFactor) {
    StravoCard {
        Row(
            modifier = Modifier.padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompassMark(modifier = Modifier.size(if (formFactor == FormFactor.Tv) 86.dp else 64.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = routeLabel(route), style = StravoTypography.SectionTitle, color = StravoColors.Graphite)
                Text(
                    text = "Экран STRAVO готовится к наполнению",
                    style = StravoTypography.Body,
                    color = StravoColors.GraphiteMuted,
                )
                if (route == StravoRoute.Home) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PencilPowerButton(
                        state = VpnConnectionState.Idle,
                        enabled = false,
                        onClick = {},
                    )
                }
            }
        }
    }
}
