package com.stravo.vpn.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stravo.vpn.R
import com.stravo.vpn.ui.components.PaperCanvas
import com.stravo.vpn.ui.navigation.Destination
import com.stravo.vpn.ui.navigation.StravoBottomNav
import com.stravo.vpn.ui.navigation.StravoNavigator
import com.stravo.vpn.ui.state.HomeEvent
import com.stravo.vpn.ui.state.Notice
import com.stravo.vpn.telegram.BotLaunchResult
import com.stravo.vpn.telegram.BotLinkLauncher
import com.stravo.vpn.ui.state.StravoViewModel
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** Корень мобильного приложения: один NavHost-стек и нижняя навигация. */
@Composable
fun MobileRoot(
    viewModel: StravoViewModel,
    modifier: Modifier = Modifier,
) {
    val navigator = rememberSaveable(saver = StravoNavigator.saver()) { StravoNavigator() }
    val context = LocalContext.current
    val state by viewModel.home.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val palette = LocalStravoPalette.current

    BackHandler(enabled = navigator.canGoBack) { navigator.back() }

    PaperCanvas(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (navigator.current) {
                    Destination.HOME -> HomeScreen(
                        state = state,
                        onEvent = viewModel::onEvent,
                        onOpenLocations = { navigator.select(Destination.LOCATIONS) },
                        onOpenProfile = { navigator.select(Destination.PROFILE) },
                        onOpenSettings = { navigator.select(Destination.SETTINGS) },
                    )

                    Destination.LOCATIONS -> LocationsScreen(
                        state = state,
                        onEvent = viewModel::onEvent,
                        onBack = { navigator.back() },
                    )

                    Destination.PROFILE -> ProfileScreen(
                        state = state,
                        onConnectTv = { navigator.push(Destination.CONNECT_TV) },
                        onBack = { navigator.back() },
                    )

                    Destination.CONNECT_TV -> ConnectTvScreen(
                        pairing = pairing,
                        onRefresh = { viewModel.refreshPairingExpiry() },
                        onScan = { navigator.push(Destination.SCANNER) },
                        onBack = { navigator.back() },
                    )

                    Destination.SCANNER -> QrScannerScreen(
                        onCode = { value ->
                            navigator.back()
                            openScannedCode(context, value, viewModel)
                        },
                        onBack = { navigator.back() },
                    )

                    Destination.SETTINGS -> SettingsScreen(
                        viewModel = viewModel,
                        onBack = { navigator.back() },
                    )
                }

                if (state.notice != null) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        NoticeBar(
                            text = noticeText(state.notice),
                            modifier = Modifier
                                .padding(StravoTokens.SpaceLg)
                                .fillMaxWidth(),
                        )
                    }
                }
            }

            StravoBottomNav(
                current = navigator.current,
                onSelect = { destination -> navigator.select(destination) },
            )
        }
    }
}

@Composable
private fun noticeText(notice: Notice?): String = when (notice) {
    Notice.LINK_COPIED -> stringResource(id = R.string.notice_link_copied)
    Notice.BOT_UNAVAILABLE -> stringResource(id = R.string.notice_bot_unavailable)
    Notice.CORE_MISSING -> stringResource(id = R.string.notice_core_missing)
    Notice.PAIRING_BACKEND_MISSING -> stringResource(id = R.string.notice_pairing_backend_missing)
    Notice.PROFILE_MISSING -> stringResource(id = R.string.notice_profile_missing)
    Notice.SCAN_INVALID -> stringResource(id = R.string.notice_scan_invalid)
    null -> ""
}

/** Открывает ссылку из отсканированного QR: Telegram → браузер → подсказка. */
private fun openScannedCode(
    context: android.content.Context,
    value: String,
    viewModel: StravoViewModel,
) {
    val isTelegramLink = value.startsWith("https://t.me/") ||
        value.startsWith("http://t.me/") ||
        value.startsWith("tg://")
    if (!isTelegramLink) {
        viewModel.onEvent(HomeEvent.NoticeShown(Notice.SCAN_INVALID))
        return
    }
    when (BotLinkLauncher.openUrl(context, value, value)) {
        is BotLaunchResult.ManualCopy -> {
            BotLinkLauncher.copyToClipboard(context, value)
            viewModel.onEvent(HomeEvent.NoticeShown(Notice.LINK_COPIED))
        }

        else -> Unit
    }
}

@Composable
private fun NoticeBar(text: String, modifier: Modifier = Modifier) {
    val palette = LocalStravoPalette.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(StravoTokens.CardRadiusMobile))
            .background(palette.medallion)
            .padding(horizontal = StravoTokens.SpaceLg, vertical = StravoTokens.SpaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(text = text, style = StravoType.Caption, color = palette.onAccent)
    }
}
