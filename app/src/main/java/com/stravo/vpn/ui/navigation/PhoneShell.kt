package com.stravo.vpn.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.ui.components.PaperCanvas
import com.stravo.vpn.ui.theme.StravoColors

@Composable
fun PhoneShell(
    currentRoute: StravoRoute,
    onNavigate: (StravoRoute) -> Unit,
    content: @Composable () -> Unit,
) {
    PaperCanvas {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            ShellHeader(formFactor = FormFactor.Phone)
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
            PhoneBottomNav(currentRoute = currentRoute, onNavigate = onNavigate)
        }
    }
}

@Composable
private fun ShellHeader(formFactor: FormFactor) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "STRAVO VPN",
            color = StravoColors.Graphite,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
        )
        Text(
            text = formFactor.wireName.uppercase(),
            color = StravoColors.Mint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}

@Composable
private fun PhoneBottomNav(
    currentRoute: StravoRoute,
    onNavigate: (StravoRoute) -> Unit,
) {
    val routes = listOf(
        StravoRoute.Home,
        StravoRoute.Servers,
        StravoRoute.Import,
        StravoRoute.WhiteList,
        StravoRoute.Settings,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        routes.forEach { route ->
            PhoneNavItem(
                modifier = Modifier.weight(1f),
                route = route,
                selected = route == currentRoute,
                onClick = { onNavigate(route) },
            )
        }
    }
}

@Composable
private fun RowScope.PhoneNavItem(
    modifier: Modifier,
    route: StravoRoute,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) StravoColors.Mint.copy(alpha = 0.18f) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, StravoColors.Mint) else null,
        elevation = 0.dp,
    ) {
        Text(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            text = routeLabel(route),
            color = StravoColors.Graphite,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

internal fun routeLabel(route: StravoRoute): String = when (route) {
    StravoRoute.Home -> "Главная"
    StravoRoute.Servers -> "Серверы"
    StravoRoute.Import -> "Импорт"
    StravoRoute.WhiteList -> "Белые списки"
    StravoRoute.Settings -> "Настройки"
    StravoRoute.Help -> "Помощь"
}
