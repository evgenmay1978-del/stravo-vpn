package com.stravo.vpn.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import com.stravo.vpn.data.settings.SettingsRepository
import com.stravo.vpn.data.settings.StravoSettings
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.ui.navigation.StravoRoute
import com.stravo.vpn.ui.screens.common.StravoNotice
import com.stravo.vpn.ui.screens.common.StravoPrimaryButton
import com.stravo.vpn.ui.screens.common.StravoScreen
import com.stravo.vpn.ui.screens.common.StravoToggleRow
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.theme.StravoColors
import com.stravo.vpn.ui.theme.StravoTypography
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    formFactor: FormFactor,
    settingsRepository: SettingsRepository,
    onNavigate: (StravoRoute) -> Unit,
) {
    val settings by settingsRepository.settings.collectAsState(initial = StravoSettings())
    val scope = rememberCoroutineScope()

    StravoScreen(
        title = "Настройки",
        subtitle = "Параметры STRAVO VPN",
    ) {
        StravoCard {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = "Текущий режим",
                    style = StravoTypography.SectionTitle,
                    color = StravoColors.Graphite,
                )
                Text(
                    text = settings.modeLabel(formFactor),
                    style = StravoTypography.Body,
                    color = StravoColors.Mint,
                )
                Text(
                    text = if (settings.selectedProfileId == null) {
                        "Профиль не выбран"
                    } else {
                        "Профиль выбран и сохранён на этом устройстве"
                    },
                    style = StravoTypography.Caption,
                    color = StravoColors.GraphiteMuted,
                )
                Text(
                    text = if (formFactor == FormFactor.Tv) {
                        "Телевизор использует только обычный VPN"
                    } else {
                        "Телефон поддерживает обычный VPN и WhiteList"
                    },
                    style = StravoTypography.Caption,
                    color = StravoColors.GraphiteMuted,
                )
            }
        }
        StravoToggleRow(
            title = "Автоподключение",
            subtitle = "Настройка будет применена после подключения VPN-ядра",
            checked = settings.autoConnect,
            onCheckedChange = { value ->
                scope.launch { settingsRepository.update { it.copy(autoConnect = value) } }
            },
        )
        StravoToggleRow(
            title = "Переподключение",
            subtitle = "Сохранять намерение подключиться после потери сети",
            checked = settings.reconnectOnNetworkLoss,
            onCheckedChange = { value ->
                scope.launch { settingsRepository.update { it.copy(reconnectOnNetworkLoss = value) } }
            },
        )
        StravoNotice(
            title = "Версия интерфейса 0.1.0",
            message = "Импорт и настройки работают локально. Данные профилей хранятся в защищённом локальном хранилище; сетевой туннель пока не запускается.",
            accent = StravoColors.Sun,
        )
        StravoPrimaryButton(
            text = "ОТКРЫТЬ ПОМОЩЬ",
            onClick = { onNavigate(StravoRoute.Help) },
        )
    }
}

private fun StravoSettings.modeLabel(formFactor: FormFactor): String =
    if (formFactor == FormFactor.Phone && selectedModeWireName == "white_list") {
        "Обход белых списков"
    } else {
        "Обычный VPN"
    }
