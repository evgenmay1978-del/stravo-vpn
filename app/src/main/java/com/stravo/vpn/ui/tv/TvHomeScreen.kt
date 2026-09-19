package com.stravo.vpn.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stravo.vpn.R
import com.stravo.vpn.domain.model.ConnectionState
import com.stravo.vpn.ui.components.PencilButton
import com.stravo.vpn.ui.components.PowerMedallion
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.navigation.Destination
import com.stravo.vpn.ui.state.HomeUiState
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** Главный экран TV: слева знак и медальон, справа локация, профиль и быстрое подключение. */
@Composable
fun TvHomeScreen(
    state: HomeUiState,
    onNavigate: (Destination) -> Unit,
    onOpenConnectPhone: () -> Unit,
    modifier: Modifier = Modifier,
    onEvent: (com.stravo.vpn.ui.state.HomeEvent) -> Unit = {},
) {
    val palette = LocalStravoPalette.current
    val quickConnectFocus = remember { FocusRequester() }

    // Стартовый фокус — на самом логичном действии.
    LaunchedEffect(Unit) {
        runCatching { quickConnectFocus.requestFocus() }
    }

    Row(
        modifier = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StravoTokens.SpaceLg),
    ) {
        Column(
            modifier = Modifier.weight(1.2f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val size = minOf(maxWidth * 0.94f, 300.dp)
                PowerMedallion(
                    state = state.connection,
                    size = size,
                    onClick = { onEvent(com.stravo.vpn.ui.state.HomeEvent.PowerClick) },
                    stateLabel = connectionLabel(state.connection),
                    contentLabel = stringResource(id = R.string.action_connect_toggle),
                )
            }
            Text(
                text = connectionLabel(state.connection),
                style = StravoType.StatusLabel,
                color = if (state.connection is ConnectionState.Error) com.stravo.vpn.ui.theme.StravoColors.Danger else palette.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = StravoTokens.SpaceLg),
            )
            Text(
                text = stringResource(id = R.string.tagline_sub),
                style = StravoType.Tiny,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = StravoTokens.SpaceSm),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceLg),
        ) {
            TvInfoCard(
                iconRes = R.drawable.ic_globe,
                title = stringResource(id = R.string.card_location),
                subtitle = listOf(state.location.country, state.location.subtitle)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                onClick = { onNavigate(Destination.LOCATIONS) },
            )
            TvInfoCard(
                iconRes = R.drawable.ic_profile,
                title = stringResource(id = R.string.card_profile),
                subtitle = state.subscription.planName,
                onClick = { onNavigate(Destination.PROFILE) },
            )
            PencilButton(
                text = stringResource(id = R.string.cta_quick_connect),
                subtitle = stringResource(id = R.string.subscription_tv_quick_connect),
                onClick = onOpenConnectPhone,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(quickConnectFocus),
                radius = StravoTokens.ButtonRadiusTv,
                leadingIcon = painterResource(id = R.drawable.ic_quick_connect),
                trailingIcon = painterResource(id = R.drawable.ic_send),
                iconTint = androidx.compose.ui.graphics.Color(0xFFB8DECA),
            )
            Spacer(modifier = Modifier.height(StravoTokens.SpaceXs))
        }
    }
}

@Composable
internal fun TvInfoCard(
    iconRes: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    var focused by remember { mutableStateOf(false) }
    StravoCard(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        focused = focused,
        radius = StravoTokens.CardRadiusTv,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = palette.textPrimary,
                modifier = Modifier.size(26.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = StravoTokens.SpaceMd),
            ) {
                Text(text = title, style = StravoType.BodyStrong, color = palette.textPrimary)
                Text(text = subtitle, style = StravoType.Caption, color = palette.textSecondary)
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron),
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun connectionLabel(connection: ConnectionState): String = when (connection) {
    ConnectionState.Disconnected -> stringResource(id = R.string.status_disconnected)
    ConnectionState.Connecting -> stringResource(id = R.string.status_connecting)
    ConnectionState.Connected -> stringResource(id = R.string.status_connected)
    is ConnectionState.Error -> stringResource(id = R.string.status_error)
}
