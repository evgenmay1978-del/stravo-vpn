package com.stravo.vpn.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stravo.vpn.R
import com.stravo.vpn.core.StravoConfig
import com.stravo.vpn.ui.components.BrandMark
import com.stravo.vpn.ui.mobile.AppsScreen
import com.stravo.vpn.ui.components.PaperCanvas
import com.stravo.vpn.ui.navigation.Destination
import com.stravo.vpn.ui.navigation.StravoNavigator
import com.stravo.vpn.ui.state.StravoViewModel
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

/**
 * Корень Android TV: боковой рельс, контент и полоса протоколов.
 * Весь интерфейс проходится только D-pad, фокус всегда виден.
 */
@Composable
fun TvRoot(
    viewModel: StravoViewModel,
    modifier: Modifier = Modifier,
) {
    val navigator = rememberSaveable(saver = StravoNavigator.saver()) { StravoNavigator() }
    val state by viewModel.home.collectAsStateWithLifecycle()

    BackHandler(enabled = navigator.canGoBack) { navigator.back() }

    PaperCanvas(modifier = modifier.fillMaxSize(), showTexture = true) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(StravoTokens.ScreenPaddingTv),
        ) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                TvRail(
                    current = navigator.current,
                    onSelect = { destination -> navigator.select(destination) },
                    modifier = Modifier.fillMaxHeight().width(250.dp),
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (navigator.current) {
                        Destination.HOME -> TvHomeScreen(
                            state = state,
                            onNavigate = { destination -> navigator.select(destination) },
                            onOpenConnectPhone = { navigator.select(Destination.CONNECT_TV) },
                            onEvent = viewModel::onEvent,
                        )

                        Destination.LOCATIONS -> TvLocationsScreen(
                            state = state,
                            onEvent = viewModel::onEvent,
                        )

                        Destination.PROFILE -> TvProfileScreen(state = state)

                        Destination.CONNECT_TV -> TvConnectScreen(
                            viewModel = viewModel,
                            onDone = { navigator.select(Destination.HOME) },
                        )

                        Destination.SETTINGS -> TvSettingsScreen(
                            viewModel = viewModel,
                            onOpenApps = { navigator.select(Destination.APPS) },
                        )

                        // Раздельный туннель: тот же экран, что на телефоне — он весь на D-pad.
                        Destination.APPS -> AppsScreen(
                            viewModel = viewModel,
                            onBack = { navigator.select(Destination.SETTINGS) },
                        )

                        // На TV подписка приходит переносом с телефона: отдельного ввода ссылки нет.
                        Destination.ADD_SUBSCRIPTION -> TvConnectScreen(
                            viewModel = viewModel,
                            onDone = { navigator.select(Destination.HOME) },
                        )

                        // На TV сканера нет: камера и «Свободный интернет» — только телефон.
                        Destination.SCANNER -> TvHomeScreen(
                            state = state,
                            onNavigate = { destination -> navigator.select(destination) },
                            onOpenConnectPhone = { navigator.select(Destination.CONNECT_TV) },
                            onEvent = viewModel::onEvent,
                        )
                    }
                }
            }
            TvProtocolStrip(modifier = Modifier.padding(top = StravoTokens.SpaceLg))
        }
    }
}

@Composable
private fun TvRail(
    current: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceSm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = StravoTokens.SpaceXl),
        ) {
            BrandMark(size = 44.dp)
            Text(
                text = stringResource(id = R.string.app_name),
                style = StravoType.BodyStrong,
                color = palette.textPrimary,
                modifier = Modifier.padding(start = StravoTokens.SpaceMd),
            )
        }
        Destination.tvRail.forEach { destination ->
            TvRailItem(
                destination = destination,
                selected = destination == current,
                onClick = { onSelect(destination) },
            )
        }
    }
}

@Composable
private fun TvRailItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalStravoPalette.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (selected || focused) StravoTokens.ButtonRadiusTv else 12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected || focused) palette.outline.copy(alpha = if (focused) 0.92f else 0.82f) else palette.panel.copy(alpha = 0.55f))
            .border(
                width = if (focused) StravoTokens.FocusBorder else 1.dp,
                color = if (focused) palette.accent else palette.outline.copy(alpha = 0.18f),
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = StravoTokens.SpaceLg, vertical = StravoTokens.SpaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (selected || focused) palette.background else palette.textPrimary
        Icon(
            painter = painterResource(id = destination.iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(id = destination.labelRes),
            style = StravoType.Body,
            color = tint,
            modifier = Modifier.padding(start = StravoTokens.SpaceMd),
        )
    }
}

/** Полоса поддерживаемых протоколов: информационная, без ложного выбора. */
@Composable
private fun TvProtocolStrip(modifier: Modifier = Modifier) {
    val palette = LocalStravoPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StravoConfig.PROTOCOL_STRIP.forEachIndexed { index, protocol ->
            val active = index == 0
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (active) palette.accent.copy(alpha = 0.18f) else palette.panel)
                    .border(
                        width = 1.dp,
                        color = if (active) palette.accent else palette.outline.copy(alpha = 0.25f),
                        shape = CircleShape,
                    )
                    .padding(horizontal = StravoTokens.SpaceLg, vertical = StravoTokens.SpaceSm),
            ) {
                Text(
                    text = protocol,
                    style = StravoType.Caption,
                    color = if (active) palette.accent else palette.textPrimary,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(id = R.string.tv_footer_note),
            style = StravoType.Tiny,
            color = palette.textSecondary,
        )
    }
}

/** Держим утилиту под рукой: единая ширина контента на TV. */
internal val TvContentWidth = Modifier.widthIn(max = 900.dp)
