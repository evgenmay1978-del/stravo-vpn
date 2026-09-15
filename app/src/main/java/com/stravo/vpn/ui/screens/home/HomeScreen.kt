package com.stravo.vpn.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.RowScope
import com.stravo.vpn.R
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.domain.model.VpnMode
import com.stravo.vpn.ui.components.CompassMark
import com.stravo.vpn.ui.components.drawPencilRing
import com.stravo.vpn.ui.navigation.StravoRoute
import com.stravo.vpn.ui.theme.StravoColors
import com.stravo.vpn.ui.theme.StravoShapes
import com.stravo.vpn.ui.theme.StravoTypography

@Composable
fun HomeScreen(
    formFactor: FormFactor,
    onNavigate: (StravoRoute) -> Unit,
) {
    var selectedMode by remember { mutableStateOf(VpnMode.Ordinary) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HomeBrandHeader()
        Spacer(modifier = Modifier.height(14.dp))
        HomePowerControl()
        Spacer(modifier = Modifier.height(12.dp))
        HomeStatusCard()
        Spacer(modifier = Modifier.height(10.dp))
        HomeServerCard(onClick = { onNavigate(StravoRoute.Servers) })
        Spacer(modifier = Modifier.height(10.dp))
        HomeModePicker(
            formFactor = formFactor,
            selectedMode = selectedMode,
            onModeSelected = { selectedMode = it },
        )
        Spacer(modifier = Modifier.height(10.dp))
        HomeMetrics()
        Spacer(modifier = Modifier.height(12.dp))
        HomeQuickConnect()
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "ОДИН МИР. БОЛЬШЕ ВОЗМОЖНОСТЕЙ.",
            style = StravoTypography.Eyebrow,
            color = StravoColors.GraphiteMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HomeBrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(id = R.drawable.stravo_app_icon),
            contentDescription = "STRAVO VPN",
            modifier = Modifier.size(52.dp),
        )
        Column {
            Text(
                text = "STRAVO VPN",
                color = StravoColors.Graphite,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
            )
            Text(
                text = "СВОБОДА В СЕТИ",
                color = StravoColors.GraphiteMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.2.sp,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        CompassMark(modifier = Modifier.size(42.dp), color = StravoColors.Mint)
    }
}

@Composable
private fun HomePowerControl() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(216.dp),
            shape = CircleShape,
            color = StravoColors.Graphite,
            border = BorderStroke(8.dp, StravoColors.Mint.copy(alpha = 0.72f)),
            elevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawPencilRing(
                        center = center,
                        radius = size.minDimension * 0.43f,
                        color = StravoColors.Mint,
                        strokeWidth = 7.dp.toPx(),
                    )
                    drawPencilRing(
                        center = center,
                        radius = size.minDimension * 0.35f,
                        color = StravoColors.PaperLight.copy(alpha = 0.30f),
                        strokeWidth = 2.dp.toPx(),
                    )
                    val symbolRadius = size.minDimension * 0.22f
                    drawLine(
                        color = StravoColors.PaperLight,
                        start = center.copy(y = center.y - symbolRadius),
                        end = center.copy(y = center.y + symbolRadius * 0.15f),
                        strokeWidth = 8.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawArc(
                        color = StravoColors.PaperLight,
                        startAngle = -48f,
                        sweepAngle = 276f,
                        useCenter = false,
                        topLeft = center.copy(
                            x = center.x - symbolRadius,
                            y = center.y - symbolRadius,
                        ),
                        size = androidx.compose.ui.geometry.Size(symbolRadius * 2, symbolRadius * 2),
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "ГОТОВО К ПОДКЛЮЧЕНИЮ",
            style = StravoTypography.Eyebrow,
            color = StravoColors.Mint,
        )
    }
}

@Composable
private fun HomeStatusCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = StravoShapes.Card,
        color = StravoColors.PaperLight.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, StravoColors.Graphite.copy(alpha = 0.25f)),
        elevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Canvas(modifier = Modifier.size(18.dp)) {
                drawCircle(StravoColors.Mint, radius = size.minDimension * 0.32f, center = center)
                drawCircle(
                    color = StravoColors.Mint.copy(alpha = 0.30f),
                    radius = size.minDimension * 0.48f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            Column {
                Text(
                    text = "ОЖИДАЕТ ПОДКЛЮЧЕНИЯ",
                    color = StravoColors.Graphite,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
                Text(
                    text = "Выберите профиль и локацию",
                    color = StravoColors.GraphiteMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun HomeServerCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = StravoShapes.Card,
        color = StravoColors.PaperLight.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, StravoColors.Graphite.copy(alpha = 0.25f)),
        elevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlobeMark(modifier = Modifier.size(38.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Сервер не выбран",
                    style = StravoTypography.SectionTitle,
                    color = StravoColors.Graphite,
                )
                Text(
                    text = "ВИРТУАЛЬНАЯ ЛОКАЦИЯ",
                    style = StravoTypography.Eyebrow,
                    color = StravoColors.GraphiteMuted,
                )
            }
            Text(
                text = "›",
                color = StravoColors.Graphite,
                fontSize = 34.sp,
                fontWeight = FontWeight.Light,
            )
        }
    }
}

@Composable
private fun HomeModePicker(
    formFactor: FormFactor,
    selectedMode: VpnMode,
    onModeSelected: (VpnMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ModeCard(
            modifier = Modifier.weight(1f),
            mode = VpnMode.Ordinary,
            selected = selectedMode == VpnMode.Ordinary,
            onClick = { onModeSelected(VpnMode.Ordinary) },
        )
        if (formFactor == FormFactor.Phone) {
            ModeCard(
                modifier = Modifier.weight(1f),
                mode = VpnMode.WhiteList,
                selected = selectedMode == VpnMode.WhiteList,
                onClick = { onModeSelected(VpnMode.WhiteList) },
            )
        }
    }
}

@Composable
private fun ModeCard(
    modifier: Modifier,
    mode: VpnMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (selected) StravoColors.Mint else StravoColors.Graphite
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = StravoShapes.Control,
        color = if (selected) StravoColors.Mint.copy(alpha = 0.25f) else StravoColors.PaperLight.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, accent.copy(alpha = if (selected) 0.90f else 0.30f)),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = if (mode == VpnMode.Ordinary) "Обычный VPN" else "Обход белых списков",
                color = StravoColors.Graphite,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (mode == VpnMode.Ordinary) "Свободный интернет" else "Только для телефона",
                color = StravoColors.GraphiteMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HomeMetrics() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = StravoShapes.Card,
        color = StravoColors.PaperLight.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, StravoColors.Graphite.copy(alpha = 0.20f)),
        elevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetricItem(label = "Пинг", value = "—")
            MetricDivider()
            MetricItem(label = "Загрузка", value = "—")
            MetricDivider()
            MetricItem(label = "Отдача", value = "—")
        }
    }
}

@Composable
private fun RowScope.MetricItem(label: String, value: String) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = label, color = StravoColors.GraphiteMuted, fontSize = 12.sp)
        Text(text = value, color = StravoColors.Graphite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetricDivider() {
    Surface(
        modifier = Modifier
            .height(42.dp)
            .width(1.dp),
        color = StravoColors.Graphite.copy(alpha = 0.20f),
    ) {}
}

@Composable
private fun HomeQuickConnect() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = false, onClick = {}),
        shape = RoundedCornerShape(28.dp),
        color = StravoColors.Graphite.copy(alpha = 0.92f),
        border = BorderStroke(2.dp, StravoColors.Mint.copy(alpha = 0.72f)),
        elevation = 0.dp,
    ) {
        Text(
            modifier = Modifier.padding(vertical = 14.dp),
            text = "⚡  Быстрое подключение",
            color = StravoColors.PaperLight,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GlobeMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 2.dp.toPx()
        drawCircle(StravoColors.Graphite, radius = size.minDimension * 0.38f, center = center, style = Stroke(stroke))
        drawOval(
            color = StravoColors.Graphite,
            topLeft = center.copy(x = center.x - size.minDimension * 0.18f, y = center.y - size.minDimension * 0.38f),
            size = androidx.compose.ui.geometry.Size(size.minDimension * 0.36f, size.minDimension * 0.76f),
            style = Stroke(stroke),
        )
        drawLine(
            color = StravoColors.Graphite,
            start = Offset(center.x - size.minDimension * 0.38f, center.y),
            end = Offset(center.x + size.minDimension * 0.38f, center.y),
            strokeWidth = stroke,
        )
    }
}
