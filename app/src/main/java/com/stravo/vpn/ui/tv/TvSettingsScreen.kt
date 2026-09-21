package com.stravo.vpn.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.stravo.vpn.ui.settings.NetworkCheckRow
import com.stravo.vpn.ui.settings.NotificationSettingsRow
import com.stravo.vpn.ui.settings.SelectedProtocolRow
import com.stravo.vpn.ui.state.HomeEvent
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
    val state by viewModel.home.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceSm),
    ) {
        Text(
            text = stringResource(id = R.string.settings_title),
            style = StravoType.ScreenTitle,
            color = palette.textPrimary,
            modifier = Modifier.padding(bottom = StravoTokens.SpaceSm),
        )
        SelectedProtocolRow(state)
        com.stravo.vpn.ui.settings.ConnectionPreferences(viewModel)
        com.stravo.vpn.ui.settings.VpnSystemSettingsRow()
        com.stravo.vpn.ui.settings.BackupPreferences(viewModel)
        com.stravo.vpn.ui.settings.AppUpdatePreferences(viewModel.appUpdates)
        NotificationSettingsRow()
        NetworkCheckRow(state, onCheck = { viewModel.onEvent(HomeEvent.ProbeClick) })
        StravoSettingRow(iconRes = R.drawable.ic_share, title = "Скопировать журнал",
            onClick = { viewModel.copyCoreLog() })
        StravoSettingRow(iconRes = R.drawable.ic_download, title = "Сохранить журнал",
            onClick = { viewModel.saveCoreLog() })
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
