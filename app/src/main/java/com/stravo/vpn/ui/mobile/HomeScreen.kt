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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import com.stravo.vpn.ui.components.PencilIcon as Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.stravo.vpn.ui.components.pencilSurface
import com.stravo.vpn.ui.components.CoreLogCard
import com.stravo.vpn.ui.components.IconAction
import com.stravo.vpn.ui.components.PencilButton
import com.stravo.vpn.ui.components.PencilButtonStyle
import com.stravo.vpn.ui.components.PowerMedallion
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.components.SubscriptionDetails
import com.stravo.vpn.ui.components.StravoStatRow
import com.stravo.vpn.ui.components.StravoSettingRow
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
    onOpenSubscriptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    val context = LocalContext.current
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = StravoTokens.ScreenPaddingMobile),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(StravoTokens.SpaceSm))

        HomeHeader(onOpenSettings = onOpenSettings)

        Spacer(modifier = Modifier.height(StravoTokens.SpaceXs))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val medallion = minOf(maxWidth * 0.68f, 248.dp)
            PowerMedallion(
                state = state.connection,
                size = medallion,
                onClick = { onEvent(HomeEvent.PowerClick) },
                stateLabel = statusLabel(state.connection),
                contentLabel = stringResource(id = R.string.action_connect_toggle),
            )
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceMd))

        Text(
            text = statusLabel(state.connection),
            style = StravoType.StatusLabel,
            color = if (state.connection is ConnectionState.Error) com.stravo.vpn.ui.theme.StravoColors.Danger else palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = statusSubtitle(state.connection),
            style = StravoType.Tiny,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = StravoTokens.SpaceSm, start = StravoTokens.SpaceLg, end = StravoTokens.SpaceLg),
        )

        Spacer(modifier = Modifier.height(StravoTokens.SpaceLg))

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
                subtitle = subscriptionTitle(state),
                onClick = onOpenProfile,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceMd))

        SubscriptionDetails(state.selectedSubscription)
        StravoCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenSubscriptions,
            padding = StravoTokens.SpaceMd,
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

        StravoCard(modifier = Modifier.fillMaxWidth(), padding = StravoTokens.SpaceMd) {
            StravoStatRow(stats = state.stats)
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceMd))

        PencilButton(
            text = stringResource(id = R.string.cta_quick_connect),
            subtitle = stringResource(id = R.string.tv_quick_connect_sub),
            onClick = {
                when (BotLinkLauncher.openQuickConnect(context, state.formFactor)) {
                    BotLaunchResult.Telegram, BotLaunchResult.Browser -> Unit

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
            iconTint = androidx.compose.ui.graphics.Color(0xFFB8DECA),
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

        Spacer(modifier = Modifier.height(StravoTokens.SpaceSm))
        StravoSettingRow(
            iconRes = R.drawable.ic_network,
            title = stringResource(R.string.diagnostics_title),
            onClick = { diagnosticsExpanded = !diagnosticsExpanded },
            trailing = {
                Text(if (diagnosticsExpanded) "−" else "+",
                    style = StravoType.BodyStrong, color = palette.textSecondary)
            },
        )
        if (diagnosticsExpanded) {
            state.diagnostics?.let { message ->
                Text(message, style = StravoType.Caption, color = palette.textSecondary,
                    modifier = Modifier.padding(bottom = StravoTokens.SpaceSm))
            }
            CoreLogCard(title = stringResource(R.string.diag_core_log), lines = state.coreLog)
            if (state.coreLog.isNotEmpty()) {
                PencilButton(
                    text = stringResource(if (state.probing) R.string.diag_probe_running else R.string.diag_probe),
                    onClick = { onEvent(HomeEvent.ProbeClick) },
                    modifier = Modifier.fillMaxWidth().padding(top = StravoTokens.SpaceSm),
                    style = PencilButtonStyle.Secondary,
                    leadingIcon = painterResource(R.drawable.ic_network),
                )
            }
        }
        Spacer(modifier = Modifier.height(StravoTokens.SpaceMd))
    }
}

/** Шапка: знак S, название, подзаголовок и переход в настройки. */
@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    val palette = LocalStravoPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(size = 54.dp, withRing = false)
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = StravoTokens.SpaceSm),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = stringResource(id = R.string.app_name),
                style = StravoType.Wordmark.copy(fontSize = 26.sp, letterSpacing = 0.3.sp),
                color = palette.textPrimary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = stringResource(id = R.string.tagline),
                style = StravoType.Tiny.copy(fontSize = 9.sp, letterSpacing = 1.2.sp),
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
        modifier = modifier.heightIn(min = 78.dp),
        onClick = onClick,
        padding = StravoTokens.SpaceMd,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(30.dp).clip(CircleShape)
                    .pencilSurface(palette.panelSoft.copy(alpha = 0.75f), palette.outline, 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = palette.textPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = StravoTokens.SpaceSm)) {
                Text(text = title, style = StravoType.BodyStrong, color = palette.textPrimary)
                Text(
                    text = subtitle,
                    style = StravoType.Caption,
                    color = palette.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Строка локации в карточке: «Страна · Город · Протокол».
 *
 * Пока туннель поднят, показывается узел, который в нём реально работает, а не
 * выбранный в списке: смена локации на ходу ядро не перезапускает, и раньше карточка
 * показывала новый узел как подключённый. Если выбор разошёлся с туннелем — сказано прямо.
 */
private fun locationSummary(state: HomeUiState): String {
    val tunnel = state.tunnelLocation
    val location = tunnel ?: state.location
    val parts = ArrayList<String>(3)
    if (location.country.isNotBlank()) parts.add(location.country)
    val subtitle = location.subtitle
    if (subtitle.isNotBlank()) parts.add(subtitle)
    val line = parts.joinToString(" · ")
    val selectionDiffers = tunnel != null && state.location.id != tunnel.id
    return if (selectionDiffers) line + " · выбран другой узел" else line
}

@Composable
private fun statusLabel(connection: ConnectionState): String = when (connection) {
    ConnectionState.Disconnected -> stringResource(id = R.string.status_disconnected)
    ConnectionState.Connecting -> stringResource(id = R.string.status_connecting)
    ConnectionState.Connected -> stringResource(id = R.string.status_connected)
    ConnectionState.Checking -> stringResource(id = R.string.status_checking)
    ConnectionState.Degraded -> stringResource(id = R.string.status_unverified)
    is ConnectionState.Error -> stringResource(id = R.string.status_error)
}

@Composable
private fun statusSubtitle(connection: ConnectionState): String = when (connection) {
    is ConnectionState.Error -> connection.reason
    ConnectionState.Connected -> stringResource(id = R.string.status_connected_sub)
    ConnectionState.Checking -> stringResource(id = R.string.status_checking_sub)
    ConnectionState.Degraded -> stringResource(id = R.string.status_unverified_sub)
    else -> stringResource(id = R.string.tagline_sub)
}

@Composable
private fun modeTitle(state: HomeUiState): String = when (state.mode) {
    NetworkMode.NORMAL_VPN -> stringResource(id = R.string.card_mode)
    NetworkMode.FREE_INTERNET -> stringResource(id = R.string.mode_free_internet)
}

@Composable
private fun modeSubtitle(state: HomeUiState): String = stringResource(
    if (state.availableNodes.isEmpty()) R.string.subscription_picker_empty_mode
    else R.string.subscription_picker_open,
)
