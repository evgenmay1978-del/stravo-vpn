package com.stravo.vpn.ui.screens.whitelist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.domain.model.VpnMode
import com.stravo.vpn.ui.navigation.StravoRoute
import com.stravo.vpn.ui.screens.common.StravoBullet
import com.stravo.vpn.ui.screens.common.StravoNotice
import com.stravo.vpn.ui.screens.common.StravoPrimaryButton
import com.stravo.vpn.ui.screens.common.StravoScreen
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.theme.StravoColors
import com.stravo.vpn.ui.theme.StravoTypography

@Composable
fun WhiteListScreen(
    formFactor: FormFactor,
    onNavigate: (StravoRoute) -> Unit,
) {
    if (formFactor != FormFactor.Phone) return

    StravoScreen(
        title = "Обход белых списков",
        subtitle = "Специальный режим только для телефона",
    ) {
        StravoCard(borderColor = StravoColors.Mint.copy(alpha = 0.60f)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Режим WhiteList",
                    style = StravoTypography.SectionTitle,
                    color = StravoColors.Graphite,
                )
                StravoBullet(
                    title = "Отдельный профиль",
                    text = "Работает независимо от обычного VPN-профиля.",
                )
                StravoBullet(
                    title = "Только телефон",
                    text = "На Android TV этот режим не показывается и не запускается.",
                )
                StravoBullet(
                    title = "Без выдуманных параметров",
                    text = "Подключение появится только после импорта совместимой конфигурации.",
                )
            }
        }
        StravoNotice(
            title = "Профиль не подключён",
            message = "Импортируйте профиль с режимом ${VpnMode.WhiteList.wireName}, затем выберите его в разделе «Локации».",
            accent = StravoColors.Sun,
        )
        StravoPrimaryButton(
            text = "ИМПОРТИРОВАТЬ ПРОФИЛЬ",
            onClick = { onNavigate(StravoRoute.Import) },
        )
    }
}
