package com.stravo.vpn.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (currentRoute != StravoRoute.Home) {
                ShellHeader(formFactor = FormFactor.Phone)
                Spacer(modifier = Modifier.height(16.dp))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = if (currentRoute == StravoRoute.Home) Alignment.TopCenter else Alignment.Center,
            ) {
                content()
            }
            Spacer(modifier = Modifier.height(8.dp))
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
        Column(
            modifier = Modifier.padding(vertical = 7.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            PhoneNavGlyph(route = route, selected = selected)
            Text(
                text = phoneNavLabel(route),
                color = StravoColors.Graphite,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

private fun phoneNavLabel(route: StravoRoute): String = when (route) {
    StravoRoute.Home -> "Главная"
    StravoRoute.Servers -> "Локации"
    StravoRoute.Import -> "Профиль"
    StravoRoute.Settings -> "Настройки"
    else -> routeLabel(route)
}

@Composable
private fun PhoneNavGlyph(route: StravoRoute, selected: Boolean) {
    val color = if (selected) StravoColors.Mint else StravoColors.Graphite
    Canvas(modifier = Modifier.size(25.dp)) {
        val stroke = 2.2.dp.toPx()
        when (route) {
            StravoRoute.Home -> {
                val roof = Path().apply {
                    moveTo(size.width * 0.18f, size.height * 0.48f)
                    lineTo(size.width * 0.50f, size.height * 0.18f)
                    lineTo(size.width * 0.82f, size.height * 0.48f)
                }
                drawPath(roof, color, style = Stroke(stroke, cap = StrokeCap.Round))
                drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.28f, size.height * 0.45f), size = androidx.compose.ui.geometry.Size(size.width * 0.44f, size.height * 0.38f), style = Stroke(stroke))
            }
            StravoRoute.Servers -> {
                drawCircle(color, radius = size.minDimension * 0.36f, center = center, style = Stroke(stroke))
                drawLine(color, androidx.compose.ui.geometry.Offset(center.x, size.height * 0.14f), androidx.compose.ui.geometry.Offset(center.x, size.height * 0.86f), strokeWidth = stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.14f, center.y), androidx.compose.ui.geometry.Offset(size.width * 0.86f, center.y), strokeWidth = stroke)
            }
            StravoRoute.Import -> {
                drawCircle(color, radius = size.minDimension * 0.34f, center = center, style = Stroke(stroke))
                drawLine(color, androidx.compose.ui.geometry.Offset(center.x, size.height * 0.20f), androidx.compose.ui.geometry.Offset(center.x, size.height * 0.62f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.32f, size.height * 0.48f), androidx.compose.ui.geometry.Offset(center.x, size.height * 0.66f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.68f, size.height * 0.48f), androidx.compose.ui.geometry.Offset(center.x, size.height * 0.66f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
            StravoRoute.Settings -> {
                drawCircle(color, radius = size.minDimension * 0.26f, center = center, style = Stroke(stroke))
                repeat(8) { index ->
                    val angle = Math.toRadians(index * 45.0)
                    val inner = size.minDimension * 0.34f
                    val outer = size.minDimension * 0.46f
                    drawLine(
                        color,
                        androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle).toFloat() * inner, center.y + kotlin.math.sin(angle).toFloat() * inner),
                        androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle).toFloat() * outer, center.y + kotlin.math.sin(angle).toFloat() * outer),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
            else -> drawCircle(color, radius = size.minDimension * 0.35f, center = center, style = Stroke(stroke))
        }
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
