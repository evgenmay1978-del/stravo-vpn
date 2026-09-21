package com.stravo.vpn.ui.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.stravo.vpn.BuildConfig
import com.stravo.vpn.R
import com.stravo.vpn.ui.components.StravoScreenHeader
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.components.StravoSettingRow
import com.stravo.vpn.ui.components.StravoToggle
import com.stravo.vpn.ui.state.StravoViewModel
import com.stravo.vpn.ui.state.HomeEvent
import com.stravo.vpn.ui.settings.NetworkCheckRow
import com.stravo.vpn.ui.settings.NotificationSettingsRow
import com.stravo.vpn.ui.settings.SelectedProtocolRow
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.stravo.vpn.ui.components.PencilDivider

/** Подпись режима раздельного туннеля. */
@Composable
fun appsModeLabel(mode: com.stravo.vpn.data.settings.VpnAppMode): String = when (mode) {
    com.stravo.vpn.data.settings.VpnAppMode.ALL -> stringResource(id = R.string.apps_mode_all)
    com.stravo.vpn.data.settings.VpnAppMode.ONLY_SELECTED -> stringResource(id = R.string.apps_mode_only)
    com.stravo.vpn.data.settings.VpnAppMode.EXCEPT_SELECTED -> stringResource(id = R.string.apps_mode_except)
}

/** Настройки подключения, системные уведомления и диагностика. */
@Composable
fun SettingsScreen(
    viewModel: StravoViewModel,
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.home.collectAsStateWithLifecycle()
    // Вариант ядра применяется при следующем подключении, поэтому это локальное состояние.
    var variant by remember { mutableStateOf(viewModel.coreVariant()) }
    // Результат выгрузки журнала: имя файла в «Загрузках».
    var logStatus by remember { mutableStateOf<String?>(null) }
    var copyStatus by remember { mutableStateOf<String?>(null) }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = StravoTokens.ScreenPaddingMobile),
    ) {
        StravoScreenHeader(
            title = stringResource(id = R.string.settings_title),
            onBack = onBack,
            modifier = Modifier.padding(top = StravoTokens.SpaceMd),
        )

        Spacer(modifier = Modifier.height(StravoTokens.SpaceLg))
        com.stravo.vpn.ui.settings.ConnectionPreferences(viewModel)
        com.stravo.vpn.ui.settings.VpnSystemSettingsRow()
        com.stravo.vpn.ui.settings.BackupPreferences(viewModel)

        StravoCard(modifier = Modifier.fillMaxWidth(), padding = 0.dp) {
            SelectedProtocolRow(state)
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            NotificationSettingsRow()
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            NetworkCheckRow(state, onCheck = { viewModel.onEvent(HomeEvent.ProbeClick) })
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_network,
                title = stringResource(id = R.string.settings_apps),
                subtitle = appsModeLabel(settings.appMode),
                onClick = onOpenApps,
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_info,
                title = stringResource(id = R.string.settings_about),
                subtitle = "v" + BuildConfig.VERSION_NAME,
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
        }
        Spacer(modifier = Modifier.height(StravoTokens.SpaceLg))
        StravoCard(modifier = Modifier.fillMaxWidth(), padding = 0.dp) {
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
            // Диагностика ядра: варианты сборки конфига и прямой режим без узла.
            // Нужны, пока туннель поднимается, а трафик не идёт.
            StravoSettingRow(
                iconRes = R.drawable.ic_network,
                title = stringResource(id = R.string.settings_core_variant),
                subtitle = variant.label + ": " + variant.hint,
                onClick = { variant = viewModel.nextCoreVariant() },
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_download,
                title = stringResource(id = R.string.settings_core_log_save),
                subtitle = logStatus ?: stringResource(id = R.string.settings_core_log_save_sub),
                onClick = { logStatus = viewModel.saveCoreLog() },
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_share,
                title = stringResource(id = R.string.settings_core_log_copy),
                subtitle = copyStatus ?: stringResource(id = R.string.settings_core_log_copy_sub),
                onClick = {
                    val count = viewModel.copyCoreLog()
                    copyStatus = if (count > 0) "Скопировано строк: " + count else null
                },
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_power,
                title = stringResource(id = R.string.settings_core_direct),
                subtitle = stringResource(id = R.string.settings_core_direct_sub),
                trailing = {
                    StravoToggle(
                        checked = settings.coreDirectMode,
                        onCheckedChange = { value ->
                            viewModel.updateSettings { it.copy(coreDirectMode = value) }
                        },
                    )
                },
            )
        }

        }
        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))
        Text(
            text = stringResource(id = R.string.settings_footer),
            style = StravoType.Tiny,
            color = palette.textSecondary,
        )
        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))
    }
}
