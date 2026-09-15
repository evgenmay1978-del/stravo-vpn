package com.stravo.vpn.ui.screens.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.navigation.StravoRoute
import com.stravo.vpn.ui.screens.common.StravoBullet
import com.stravo.vpn.ui.screens.common.StravoPrimaryButton
import com.stravo.vpn.ui.screens.common.StravoScreen
import com.stravo.vpn.ui.screens.common.StravoSecondaryButton
import com.stravo.vpn.ui.theme.StravoColors
import com.stravo.vpn.ui.theme.StravoTypography

@Composable
fun HelpScreen(
    formFactor: FormFactor,
    onNavigate: (StravoRoute) -> Unit,
) {
    StravoScreen(
        title = "Помощь",
        subtitle = "Короткий путь к готовому профилю",
    ) {
        StravoCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Как начать",
                    style = StravoTypography.SectionTitle,
                    color = StravoColors.Graphite,
                )
                StravoBullet(
                    title = "1. Добавьте профиль",
                    text = "Откройте экран профиля и вставьте URL или конфигурацию.",
                )
                StravoBullet(
                    title = "2. Выберите локацию",
                    text = "Сохранённые профили находятся в разделе «Локации».",
                )
                StravoBullet(
                    title = "3. Подключитесь",
                    text = "Кнопка подключения станет активной после настройки совместимого VPN-ядра.",
                )
            }
        }
        StravoCard(borderColor = StravoColors.Mint.copy(alpha = 0.55f)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Ограничения по устройствам",
                    style = StravoTypography.SectionTitle,
                    color = StravoColors.Graphite,
                )
                Text(
                    text = if (formFactor == FormFactor.Tv) {
                        "На Android TV доступен только обычный VPN. Режим WhiteList здесь не показывается."
                    } else {
                        "На телефоне доступны обычный VPN и отдельный режим WhiteList."
                    },
                    style = StravoTypography.Body,
                    color = StravoColors.GraphiteMuted,
                )
            }
        }
        StravoPrimaryButton(
            text = "ДОБАВИТЬ ПРОФИЛЬ",
            onClick = { onNavigate(StravoRoute.Import) },
        )
        StravoSecondaryButton(
            text = "ОТКРЫТЬ ЛОКАЦИИ",
            onClick = { onNavigate(StravoRoute.Servers) },
        )
        Text(
            text = "STRAVO VPN · свобода в сети",
            style = StravoTypography.Eyebrow,
            color = StravoColors.GraphiteMuted,
        )
    }
}
