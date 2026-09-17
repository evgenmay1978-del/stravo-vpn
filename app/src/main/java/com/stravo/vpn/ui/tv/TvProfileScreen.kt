package com.stravo.vpn.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stravo.vpn.R
import com.stravo.vpn.telegram.BotLinks
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.components.StravoSettingRow
import com.stravo.vpn.ui.state.HomeUiState
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** TV: профиль и подписка. */
@Composable
fun TvProfileScreen(
    state: HomeUiState,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    Column(
        modifier = modifier.fillMaxSize().padding(start = StravoTokens.Space2Xl),
        verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd),
    ) {
        Text(
            text = stringResource(id = R.string.profile_title),
            style = StravoType.ScreenTitle,
            color = palette.textPrimary,
            modifier = Modifier.padding(bottom = StravoTokens.SpaceSm),
        )
        StravoCard(modifier = Modifier.fillMaxWidth(), radius = StravoTokens.CardRadiusTv) {
            Text(
                text = if (state.subscription.isActive) state.subscription.planName else stringResource(id = R.string.profile_no_subscription),
                style = StravoType.BodyStrong,
                color = palette.textPrimary,
            )
            Text(
                text = state.subscription.activeUntil?.let { stringResource(id = R.string.profile_valid_until, it) }
                    ?: stringResource(id = R.string.profile_connect_hint),
                style = StravoType.Caption,
                color = palette.textSecondary,
            )
        }
        Spacer(modifier = Modifier.height(StravoTokens.SpaceSm))
        StravoSettingRow(
            iconRes = R.drawable.ic_tv,
            title = stringResource(id = R.string.profile_connect_tv),
            subtitle = stringResource(id = R.string.profile_connect_tv_sub),
            focused = false,
        )
        StravoSettingRow(
            iconRes = R.drawable.ic_send,
            title = stringResource(id = R.string.profile_support),
            subtitle = BotLinks.supportHandle(),
            focused = false,
        )
        Text(
            text = stringResource(id = R.string.profile_security_note),
            style = StravoType.Tiny,
            color = palette.textSecondary,
        )
    }
}
