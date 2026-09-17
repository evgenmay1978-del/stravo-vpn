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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.stravo.vpn.BuildConfig
import com.stravo.vpn.R
import com.stravo.vpn.core.StravoConfig
import com.stravo.vpn.ui.components.StravoScreenHeader
import com.stravo.vpn.ui.components.StravoSettingRow
import com.stravo.vpn.ui.components.StravoToggle
import com.stravo.vpn.ui.state.StravoViewModel
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

/** Настройки: протокол, переключатели, язык и сведения о сборке. */
@Composable
fun SettingsScreen(
    viewModel: StravoViewModel,
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Вариант ядра применяется при следующем подключении, поэтому это локальное состояние.
    var variant by remember { mutableStateOf(viewModel.coreVariant()) }

    val protocols = StravoConfig.PROTOCOLS
    val languages = listOf("Русский", "English")

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

        Column(modifier = Modifier.fillMaxWidth()) {
            StravoSettingRow(
                iconRes = R.drawable.ic_settings,
                title = stringResource(id = R.string.settings_protocol),
                subtitle = settings.protocol,
                onClick = {
                    val index = protocols.indexOf(settings.protocol)
                    val next = protocols[(index + 1) % protocols.size]
                    viewModel.updateSettings { it.copy(protocol = next) }
                },
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
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
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
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
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_power,
                title = stringResource(id = R.string.settings_start_on_boot),
                trailing = {
                    StravoToggle(
                        checked = settings.startOnBoot,
                        onCheckedChange = { value -> viewModel.updateSettings { it.copy(startOnBoot = value) } },
                    )
                },
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_wifi,
                title = stringResource(id = R.string.settings_network_check),
                trailing = {
                    StravoToggle(
                        checked = settings.networkCheck,
                        onCheckedChange = { value -> viewModel.updateSettings { it.copy(networkCheck = value) } },
                    )
                },
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_network,
                title = stringResource(id = R.string.settings_apps),
                subtitle = appsModeLabel(settings.appMode),
                onClick = onOpenApps,
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_globe,
                title = stringResource(id = R.string.settings_language),
                subtitle = settings.language,
                onClick = {
                    val index = languages.indexOf(settings.language)
                    val next = languages[(index + 1) % languages.size]
                    viewModel.updateSettings { it.copy(language = next) }
                },
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_info,
                title = stringResource(id = R.string.settings_about),
                subtitle = "v" + BuildConfig.VERSION_NAME,
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
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

        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))
        Text(
            text = stringResource(id = R.string.settings_footer),
            style = StravoType.Tiny,
            color = palette.textSecondary,
        )
        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))
    }
}
