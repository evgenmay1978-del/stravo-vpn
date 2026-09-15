package com.stravo.vpn.ui.screens.servers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stravo.vpn.data.profile.ProfileRepository
import com.stravo.vpn.data.settings.SettingsRepository
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.domain.model.VpnMode
import com.stravo.vpn.domain.model.VpnProfileSummary
import com.stravo.vpn.ui.navigation.StravoRoute
import com.stravo.vpn.ui.screens.common.StravoNotice
import com.stravo.vpn.ui.screens.common.StravoPrimaryButton
import com.stravo.vpn.ui.screens.common.StravoScreen
import com.stravo.vpn.ui.theme.StravoColors
import com.stravo.vpn.ui.theme.StravoShapes
import com.stravo.vpn.ui.theme.StravoTypography
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ServersScreen(
    formFactor: FormFactor,
    profileRepository: ProfileRepository,
    settingsRepository: SettingsRepository,
    onNavigate: (StravoRoute) -> Unit,
) {
    var profiles by remember { mutableStateOf<List<VpnProfileSummary>>(emptyList()) }
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    var isLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(profileRepository, settingsRepository, formFactor) {
        selectedProfileId = settingsRepository.settings.first().selectedProfileId
        profiles = profileRepository.listProfiles().filter { profile ->
            formFactor == FormFactor.Phone || profile.mode == VpnMode.Ordinary
        }
        isLoaded = true
    }

    StravoScreen(
        title = "Локации",
        subtitle = if (formFactor == FormFactor.Tv) {
            "Обычный VPN для телевизора"
        } else {
            "Выберите сохранённый маршрут"
        },
    ) {
        when {
            !isLoaded -> StravoNotice(
                title = "Загружаем профили",
                message = "Проверяем локальное хранилище приложения.",
            )

            profiles.isEmpty() -> {
                StravoNotice(
                    title = "Профилей пока нет",
                    message = if (formFactor == FormFactor.Tv) {
                        "Добавьте обычный VPN-профиль, чтобы выбрать локацию."
                    } else {
                        "Добавьте ссылку или конфигурацию на экране профиля."
                    },
                )
                StravoPrimaryButton(
                    text = "ДОБАВИТЬ ПРОФИЛЬ",
                    onClick = { onNavigate(StravoRoute.Import) },
                )
            }

            else -> {
                profiles.forEach { profile ->
                    ProfileCard(
                        profile = profile,
                        selected = profile.id.value == selectedProfileId,
                        onClick = {
                            selectedProfileId = profile.id.value
                            scope.launch {
                                settingsRepository.update { settings ->
                                    settings.copy(
                                        firstRunComplete = true,
                                        selectedProfileId = profile.id.value,
                                        selectedModeWireName = profile.mode.wireName,
                                    )
                                }
                            }
                        },
                    )
                }
                StravoNotice(
                    title = "Подключение ещё не запущено",
                    message = "Профиль и выбор локации сохраняются локально. Запуск туннеля появится после подключения совместимого VPN-ядра.",
                    accent = StravoColors.Sun,
                )
                StravoPrimaryButton(
                    text = "ДОБАВИТЬ ЕЩЁ ПРОФИЛЬ",
                    onClick = { onNavigate(StravoRoute.Import) },
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: VpnProfileSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (selected) StravoColors.Mint else StravoColors.Graphite
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = StravoShapes.Card,
        color = if (selected) StravoColors.Mint.copy(alpha = 0.20f) else StravoColors.PaperLight.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, accent.copy(alpha = if (selected) 0.90f else 0.25f)),
        elevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = StravoColors.Graphite,
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    text = profile.modeLabel(),
                    color = StravoColors.PaperLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.displayName,
                    style = StravoTypography.SectionTitle,
                    color = StravoColors.Graphite,
                )
                Text(
                    text = "${profile.endpointCount} локац. · ${if (selected) "ВЫБРАНО" else "НАЖМИТЕ ДЛЯ ВЫБОРА"}",
                    style = StravoTypography.Caption,
                    color = StravoColors.GraphiteMuted,
                )
            }
            if (selected) {
                Text(
                    text = "✓",
                    color = StravoColors.Mint,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun VpnProfileSummary.modeLabel(): String = when (mode) {
    VpnMode.Ordinary -> "VPN"
    VpnMode.WhiteList -> "WL"
}
