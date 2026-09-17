package com.stravo.vpn.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stravo.vpn.BuildConfig
import com.stravo.vpn.R
import com.stravo.vpn.ui.components.StravoSettingRow
import com.stravo.vpn.ui.mobile.appsModeLabel
import com.stravo.vpn.ui.components.StravoToggle
import com.stravo.vpn.ui.state.StravoViewModel
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** TV: настройки без мобильных жестов — всё доступно D-pad. */
@Composable
fun TvSettingsScreen(
    viewModel: StravoViewModel,
    onOpenApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize().padding(start = StravoTokens.Space2Xl),
        verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceSm),
    ) {
        Text(
            text = stringResource(id = R.string.settings_title),
            style = StravoType.ScreenTitle,
            color = palette.textPrimary,
            modifier = Modifier.padding(bottom = StravoTokens.SpaceSm),
        )
        StravoSettingRow(
            iconRes = R.drawable.ic_settings,
            title = stringResource(id = R.string.settings_protocol),
            subtitle = settings.protocol,
        )
        StravoSettingRow(
            iconRes = R.drawable.ic_bell,
            title = stringResource(id = R.string.settings_notifications),
            trailing = {
                StravoToggle(
                    checked = settings.notifications,
                    onCheckedChange = { value -> viewModel.updateSettings { it.copy(notifications = value) } },
                )
            },
        )
        StravoSettingRow(
            iconRes = R.drawable.ic_quick_connect,
            title = stringResource(id = R.string.settings_autoconnect),
            trailing = {
                StravoToggle(
                    checked = settings.autoConnect,
                    onCheckedChange = { value -> viewModel.updateSettings { it.copy(autoConnect = value) } },
                )
            },
        )
        StravoSettingRow(
            iconRes = R.drawable.ic_network,
            title = stringResource(id = R.string.settings_network_check),
            trailing = {
                StravoToggle(
                    checked = settings.networkCheck,
                    onCheckedChange = { value -> viewModel.updateSettings { it.copy(networkCheck = value) } },
                )
            },
        )
        StravoSettingRow(
            iconRes = R.drawable.ic_network,
            title = stringResource(id = R.string.settings_apps),
            subtitle = appsModeLabel(settings.appMode),
            onClick = onOpenApps,
        )
        StravoSettingRow(
            iconRes = R.drawable.ic_info,
            title = stringResource(id = R.string.settings_about),
            subtitle = "v" + BuildConfig.VERSION_NAME,
        )
    }
}
