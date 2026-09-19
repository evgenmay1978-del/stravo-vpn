package com.stravo.vpn.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stravo.vpn.R
import com.stravo.vpn.pairing.PairingStatus
import com.stravo.vpn.telegram.BotLinks
import com.stravo.vpn.ui.components.PencilButton
import com.stravo.vpn.ui.components.PencilButtonStyle
import com.stravo.vpn.ui.components.QrCodeView
import com.stravo.vpn.ui.state.StravoViewModel
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoColors
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * TV: перенос подписки с телефона.
 * QR содержит только одноразовый токен, статус приходит с сервера — без фальшивого «успеха».
 */
@Composable
fun TvConnectScreen(
    viewModel: StravoViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val token by viewModel.pairingToken.collectAsStateWithLifecycle()
    val secondsLeft by viewModel.pairingSecondsLeft.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val refreshFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (token == null) viewModel.beginPairing()
        runCatching { refreshFocus.requestFocus() }
    }

    if (pairing.status == PairingStatus.SUCCESS) {
        TvPairingSuccess(onDone = onDone, modifier = modifier)
        return
    }

    val palette = LocalStravoPalette.current
    val steps = listOf(
        stringResource(id = R.string.tv_pairing_step_1),
        stringResource(id = R.string.tv_pairing_step_2),
        stringResource(id = R.string.tv_pairing_step_3),
        stringResource(id = R.string.tv_pairing_step_4),
    )

    Row(
        modifier = modifier.fillMaxSize().padding(start = StravoTokens.Space2Xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StravoTokens.Space2Xl),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(id = R.string.tv_pairing_title),
                style = StravoType.ScreenTitle,
                color = palette.textPrimary,
            )
            Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))
            steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier.padding(vertical = StravoTokens.SpaceSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StepDot(number = index + 1)
                    Text(
                        text = step,
                        style = StravoType.Body,
                        color = palette.textPrimary,
                        modifier = Modifier.padding(start = StravoTokens.SpaceMd),
                    )
                }
            }

            Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))

            Text(
                text = countdownText(secondsLeft),
                style = StravoType.BodyStrong,
                color = if (pairing.status == PairingStatus.EXPIRED) palette.accent else palette.textSecondary,
            )

            Spacer(modifier = Modifier.height(StravoTokens.SpaceSm))

            Text(
                text = if (pairing.backendConfigured) {
                    stringResource(id = R.string.tv_pairing_waiting)
                } else {
                    stringResource(id = R.string.pairing_backend_missing)
                },
                style = StravoType.Caption,
                color = palette.textSecondary,
            )

            Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))

            PencilButton(
                text = stringResource(id = R.string.tv_pairing_refresh),
                onClick = { scope.launch { viewModel.renewPairing() } },
                modifier = Modifier.focusRequester(refreshFocus),
                radius = StravoTokens.ButtonRadiusTv,
                style = PencilButtonStyle.Primary,
                leadingIcon = painterResource(id = R.drawable.ic_refresh),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(StravoTokens.CardRadiusTv))
                    .background(StravoColors.PaperLight)
                    .border(2.dp, palette.outline.copy(alpha = 0.35f), RoundedCornerShape(StravoTokens.CardRadiusTv))
                    .padding(StravoTokens.SpaceLg),
            ) {
                val payload = token?.let { BotLinks.pairingHttps(it) }
                if (payload != null) {
                    QrCodeView(
                        payload = payload,
                        modifier = Modifier.fillMaxSize(),
                        centerMarkFraction = 0.24f,
                    )
                } else {
                    Text(
                        text = stringResource(id = R.string.tv_pairing_preparing),
                        style = StravoType.Caption,
                        color = StravoColors.Graphite,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Text(
                text = stringResource(id = R.string.tv_pairing_scan_hint),
                style = StravoType.Caption,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = StravoTokens.SpaceMd),
            )
        }
    }
}

@Composable
private fun TvPairingSuccess(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalStravoPalette.current
    val actionFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { actionFocus.requestFocus() } }

    Column(
        modifier = modifier.fillMaxSize().padding(start = StravoTokens.Space2Xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = 0.16f))
                .border(2.dp, palette.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "✓", style = StravoType.Wordmark, color = palette.accent)
        }
        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))
        Text(
            text = stringResource(id = R.string.tv_success_title),
            style = StravoType.StatusLabel,
            color = palette.textPrimary,
        )
        Text(
            text = stringResource(id = R.string.tv_success_sub),
            style = StravoType.Body,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = StravoTokens.SpaceSm),
        )
        Spacer(modifier = Modifier.height(StravoTokens.Space2Xl))
        PencilButton(
            text = stringResource(id = R.string.tv_success_action),
            onClick = onDone,
            focused = focused,
            modifier = Modifier
                .focusRequester(actionFocus)
                .onFocusChanged { focused = it.isFocused },
            radius = StravoTokens.ButtonRadiusTv,
        )
    }
}

@Composable
private fun StepDot(number: Int) {
    val palette = LocalStravoPalette.current
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(palette.accent.copy(alpha = 0.18f))
            .border(1.dp, palette.accent.copy(alpha = 0.7f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = StravoType.Tiny,
            color = palette.accent,
        )
    }
}

private fun countdownText(secondsLeft: Long): String {
    val minutes = secondsLeft / 60
    val seconds = secondsLeft % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

/** Не используется в вёрстке напрямую — держим цвет явным, чтобы тёмная тема не «текла». */
internal val TvQrInk: Color = StravoColors.Graphite
