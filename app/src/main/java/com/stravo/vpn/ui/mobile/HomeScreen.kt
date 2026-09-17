package com.stravo.vpn.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stravo.vpn.R
import com.stravo.vpn.domain.model.ConnectionState
import com.stravo.vpn.domain.model.NetworkMode
import com.stravo.vpn.domain.policy.CapabilityPolicy
import com.stravo.vpn.telegram.BotLaunchResult
import com.stravo.vpn.telegram.BotLinkLauncher
import com.stravo.vpn.telegram.BotLinks
import com.stravo.vpn.ui.components.BrandMark
import com.stravo.vpn.ui.components.IconAction
import com.stravo.vpn.ui.components.PencilButton
import com.stravo.vpn.ui.components.PencilButtonStyle
import com.stravo.vpn.ui.components.PowerMedallion
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.components.StravoStatRow
import com.stravo.vpn.ui.state.HomeEvent
import com.stravo.vpn.ui.state.HomeUiState
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/**
 * Главный экран телефона по макету: шапка с знаком S, медальон подключения,
 * две карточки-сводки, режим, статистика и «Быстрое подключение».
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onEvent: (HomeEvent) -> Unit,
    onOpenLocations: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = StravoTokens.ScreenPaddingMobile),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(StravoTokens.SpaceSm))

        HomeHeader(onOpenSettings = onOpenSettings)

        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val medallion = minOf(maxWidth * 0.62f, 240.dp)
            PowerMedallion(
                state = state.connection,
                size = medallion,
                onClick = { onEvent(HomeEvent.PowerClick) },
                stateLabel = statusLabel(state.connection),
                contentLabel = stringResource(id = R.string.action_connect_toggle),
            )
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceLg))

        Text(
            text = statusLabel(state.connection),
            style = StravoType.StatusLabel,
            color = if (state.connection is ConnectionState.Error) palette.accent else palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = statusSubtitle(state.connection),
            style = StravoType.Tiny,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = StravoTokens.SpaceSm, start = StravoTokens.SpaceLg, end = StravoTokens.SpaceLg),
        )

        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd),
        ) {
            SummaryCard(
                iconRes = R.drawable.ic_location,
                title = stringResource(id = R.string.card_location),
                subtitle = locationSummary(state),
                onClick = onOpenLocations,
                modifier = Modifier.weight(1f),
            )
            SummaryCard(
                iconRes = R.drawable.ic_profile,
                title = stringResource(id = R.string.card_profile),
                subtitle = state.subscription.planName,
                onClick = onOpenProfile,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceMd))

        StravoCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onEvent(HomeEvent.ModeSelected(nextMode(state))) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_vpn),
                    contentDescription = null,
                    tint = palette.textPrimary,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = StravoTokens.SpaceMd),
                ) {
                    Text(
                        text = modeTitle(state),
                        style = StravoType.BodyStrong,
                        color = palette.textPrimary,
                    )
                    Text(
                        text = modeSubtitle(state),
                        style = StravoType.Caption,
                        color = palette.textSecondary,
                    )
                }
                if (CapabilityPolicy.availableModes(state.formFactor).size > 1) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron),
                        contentDescription = null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceMd))

        StravoCard(modifier = Modifier.fillMaxWidth()) {
            StravoStatRow(stats = state.stats)
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))

        PencilButton(
            text = stringResource(id = R.string.cta_quick_connect),
            subtitle = stringResource(id = R.string.tv_quick_connect_sub),
            onClick = {
                when (BotLinkLauncher.openQuickConnect(context, state.formFactor)) {
                    BotLaunchResult.Telegram, BotLaunchResult.Browser ->
                        onEvent(HomeEvent.NoticeShown(com.stravo.vpn.ui.state.Notice.PAIRING_BACKEND_MISSING))

                    is BotLaunchResult.ManualCopy -> {
                        BotLinkLauncher.copyToClipboard(context, BotLinks.quickConnectHttps(state.formFactor))
                        onEvent(HomeEvent.NoticeShown(com.stravo.vpn.ui.state.Notice.LINK_COPIED))
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = StravoTokens.ContentMaxWidthMobile),
            style = PencilButtonStyle.Primary,
            leadingIcon = painterResource(id = R.drawable.ic_quick_connect),
            trailingIcon = painterResource(id = R.drawable.ic_send),
            iconTint = palette.accent,
        )

        if (!state.subscription.isActive) {
            Text(
                text = stringResource(id = R.string.notice_subscription_required),
                style = StravoType.Tiny,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = StravoTokens.SpaceMd, start = StravoTokens.SpaceLg, end = StravoTokens.SpaceLg),
            )
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))
    }
}

/** Шапка: знак S, название, подзаголовок и переход в настройки. */
@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    val palette = LocalStravoPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(size = 38.dp, withRing = true)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = StravoTokens.SpaceMd),
        ) {
            Text(
                text = stringResource(id = R.string.app_name),
                style = StravoType.Wordmark.copy(fontSize = 20.sp, letterSpacing = 1.2.sp),
                color = palette.textPrimary,
            )
            Text(
                text = stringResource(id = R.string.tagline),
                style = StravoType.Tiny.copy(letterSpacing = 2.4.sp),
                color = palette.textSecondary,
            )
        }
        IconAction(
            iconRes = R.drawable.ic_settings,
            label = stringResource(id = R.string.settings_title),
            onClick = onOpenSettings,
        )
    }
}

/** Карточка-сводка: значок в круге, заголовок и подпись. */
@Composable
private fun SummaryCard(
    iconRes: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    StravoCard(
        modifier = modifier,
        onClick = onClick,
        padding = StravoTokens.SpaceLg,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(palette.panelSoft)
                .border(1.dp, palette.outline.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = palette.textPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = title,
            style = StravoType.BodyStrong,
            color = palette.textPrimary,
            modifier = Modifier.padding(top = StravoTokens.SpaceMd),
        )
        Text(
            text = subtitle,
            style = StravoType.Caption,
            color = palette.textSecondary,
            maxLines = 2,
        )
    }
}

/** «Авто · Автоматический сервер» без висящего разделителя у пустого города. */
private fun locationSummary(state: HomeUiState): String =
    listOf(state.location.country, state.location.city)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

@Composable
private fun statusLabel(connection: ConnectionState): String = when (connection) {
    ConnectionState.Disconnected -> stringResource(id = R.string.status_disconnected)
    ConnectionState.Connecting -> stringResource(id = R.string.status_connecting)
    ConnectionState.Connected -> stringResource(id = R.string.status_connected)
    is ConnectionState.Error -> stringResource(id = R.string.status_error)
}

@Composable
private fun statusSubtitle(connection: ConnectionState): String = when (connection) {
    is ConnectionState.Error -> connection.reason
    ConnectionState.Connected -> stringResource(id = R.string.status_connected_sub)
    else -> stringResource(id = R.string.tagline_sub)
}

@Composable
private fun modeTitle(state: HomeUiState): String = when (state.mode) {
    NetworkMode.NORMAL_VPN -> stringResource(id = R.string.card_mode)
    NetworkMode.FREE_INTERNET -> stringResource(id = R.string.mode_free_internet)
}

@Composable
private fun modeSubtitle(state: HomeUiState): String = when (state.mode) {
    NetworkMode.NORMAL_VPN -> stringResource(id = R.string.card_mode_sub)
    NetworkMode.FREE_INTERNET -> stringResource(id = R.string.mode_free_internet_sub)
}

private fun nextMode(state: HomeUiState): NetworkMode {
    val modes = CapabilityPolicy.availableModes(state.formFactor)
    if (modes.size <= 1) return NetworkMode.NORMAL_VPN
    val index = modes.indexOf(state.mode)
    return modes[(index + 1) % modes.size]
}
