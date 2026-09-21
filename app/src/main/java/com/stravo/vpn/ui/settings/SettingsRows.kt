package com.stravo.vpn.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import com.stravo.vpn.R
import com.stravo.vpn.ui.components.StravoSettingRow
import com.stravo.vpn.ui.state.HomeUiState
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

@Composable
internal fun SelectedProtocolRow(state: HomeUiState) {
    val node = state.nodeForLocation()
    StravoSettingRow(
        iconRes = R.drawable.ic_settings,
        title = stringResource(R.string.settings_selected_protocol),
        subtitle = node?.let {
            state.connectedLocation(it).protocol ?: it.protocolLabel
        } ?: stringResource(R.string.settings_no_selected_node),
    )
}

@Composable
internal fun NotificationSettingsRow() {
    val context = LocalContext.current
    var failedToOpen by rememberSaveable { mutableStateOf(false) }
    StravoSettingRow(
        iconRes = R.drawable.ic_bell,
        title = stringResource(R.string.settings_notifications),
        subtitle = stringResource(
            if (failedToOpen) R.string.settings_notifications_unavailable
            else R.string.settings_notifications_system,
        ),
        onClick = { failedToOpen = !openNotificationSettings(context) },
    )
}

@Composable
internal fun VpnSystemSettingsRow() {
    val context = LocalContext.current
    StravoSettingRow(iconRes = R.drawable.ic_vpn,
        title = "Постоянный VPN и блокировка без VPN",
        subtitle = "Системная защита Android. Сначала подключитесь обычной кнопкой VPN.",
        onClick = {
            if (runCatching { context.startActivity(Intent("android.settings.VPN_SETTINGS")) }.isFailure)
                runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        })
}

private fun openNotificationSettings(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(notifications) }.isSuccess) return true
    }
    val details = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(details) }.isSuccess
}

@Composable
internal fun NetworkCheckRow(state: HomeUiState, onCheck: () -> Unit) {
    var showResult by rememberSaveable { mutableStateOf(false) }
    val canCheck = !state.probing && !state.connection.isBusy
    StravoSettingRow(
        modifier = Modifier.semantics { if (!canCheck) disabled() },
        iconRes = R.drawable.ic_wifi,
        title = stringResource(
            if (state.probing) R.string.settings_network_running
            else R.string.settings_network_action,
        ),
        subtitle = stringResource(
            if (state.connection.isBusy) R.string.settings_network_connecting
            else R.string.settings_network_scope,
        ),
        // Keep D-pad focus on the row while the check is running.
        onClick = {
            if (canCheck) {
                showResult = true
                onCheck()
            }
        },
    )
    if (showResult && !state.probing) {
        val results = state.coreLog.filter { it.contains("проба:") }.takeLast(3)
        if (results.isNotEmpty()) {
            Text(
                text = results.joinToString("\n"),
                style = StravoType.Caption,
                color = LocalStravoPalette.current.textSecondary,
                modifier = Modifier.padding(
                    horizontal = StravoTokens.SpaceLg,
                    vertical = StravoTokens.SpaceSm,
                ),
            )
        }
    }
}
