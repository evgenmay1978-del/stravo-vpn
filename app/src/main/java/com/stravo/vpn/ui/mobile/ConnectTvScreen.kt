package com.stravo.vpn.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import com.stravo.vpn.R
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.pairing.PairingState
import com.stravo.vpn.telegram.BotLinkLauncher
import com.stravo.vpn.telegram.BotLinks
import com.stravo.vpn.ui.components.PairingIllustration
import com.stravo.vpn.ui.components.PencilButton
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.components.StravoScreenHeader
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** Экран телефона «Подключить ТВ»: пошаговая инструкция и переход в бота. */
@Composable
fun ConnectTvScreen(
    pairing: PairingState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    val context = LocalContext.current
    var showHelp by rememberSaveable { mutableStateOf(false) }

    val steps = listOf(
        stringResource(id = R.string.connect_tv_step_1),
        stringResource(id = R.string.connect_tv_step_2),
        stringResource(id = R.string.connect_tv_step_3),
        stringResource(id = R.string.connect_tv_step_4),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = StravoTokens.ScreenPaddingMobile),
    ) {
        StravoScreenHeader(
            title = stringResource(id = R.string.connect_tv_title),
            subtitle = stringResource(id = R.string.connect_tv_sub),
            onBack = onBack,
            modifier = Modifier.padding(top = StravoTokens.SpaceMd),
        )

        Spacer(modifier = Modifier.height(StravoTokens.SpaceLg))

        StravoCard(modifier = Modifier.fillMaxWidth()) {
            PairingIllustration(
                qrPayload = BotLinks.quickConnectHttps(FormFactor.PHONE),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceMd))

        StravoCard(modifier = Modifier.fillMaxWidth()) {
            steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = StravoTokens.SpaceSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StepBadge(number = index + 1)
                    Text(
                        text = step,
                        style = StravoType.Body,
                        color = palette.textPrimary,
                        modifier = Modifier.padding(start = StravoTokens.SpaceMd),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceLg))

        if (!pairing.backendConfigured) {
            Text(
                text = stringResource(id = R.string.pairing_backend_missing),
                style = StravoType.Caption,
                color = palette.textSecondary,
            )
            Spacer(modifier = Modifier.height(StravoTokens.SpaceMd))
        }

        PencilButton(
            text = stringResource(id = R.string.connect_tv_open_bot),
            onClick = {
                BotLinkLauncher.openUrl(
                    context = context,
                    primary = BotLinks.quickConnectInApp(FormFactor.PHONE),
                    fallback = BotLinks.quickConnectHttps(FormFactor.PHONE),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(StravoTokens.SpaceMd))

        Text(
            text = stringResource(id = R.string.connect_tv_how),
            style = StravoType.Caption,
            color = palette.accent,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .clickable { showHelp = !showHelp }
                .padding(vertical = StravoTokens.SpaceSm),
        )

        if (showHelp) {
            Text(
                text = stringResource(id = R.string.connect_tv_help_body),
                style = StravoType.Caption,
                color = palette.textSecondary,
            )
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))
        Text(
            text = stringResource(id = R.string.pairing_security_note),
            style = StravoType.Tiny,
            color = palette.textSecondary,
        )
        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))
    }
}

@Composable
internal fun StepBadge(number: Int) {
    val palette = LocalStravoPalette.current
    Column(
        modifier = Modifier.size(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = number.toString(), style = StravoType.BodyStrong, color = palette.accent)
    }
}
