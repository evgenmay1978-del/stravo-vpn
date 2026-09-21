package com.stravo.vpn.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stravo.vpn.BuildConfig
import com.stravo.vpn.R
import com.stravo.vpn.data.update.AppUpdates
import com.stravo.vpn.ui.components.*
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType
import kotlinx.coroutines.launch

/** Render inside the existing scrolling settings content on both phone and TV. */
@Composable
fun AppUpdatePreferences(updates: AppUpdates, modifier: Modifier = Modifier) {
    val state by updates.state.collectAsStateWithLifecycle()
    val prefs by updates.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var installAllowed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        installAllowed = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    }
    StravoCard(modifier = modifier.fillMaxWidth(), padding = 0.dp) {
        StravoSettingRow(R.drawable.ic_settings, "Обновления приложения",
            subtitle = "STRAVO ${BuildConfig.VERSION_NAME}",
            modifier = Modifier.semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" },
            onClick = { expanded = !expanded }, trailing = {
                Text(if (expanded) "−" else "+", color = LocalStravoPalette.current.textPrimary)
            })
        if (expanded) {
            UpdateToggle("Проверять автоматически", "Проверка новых версий примерно раз в сутки.", prefs.autoCheck) {
                updates.updatePreferences(autoCheck = it)
            }
            UpdateToggle("Скачивать по Wi-Fi", "Только подтверждённый безлимитный Wi-Fi. Если сеть определяется как VPN, доступна ручная загрузка.", prefs.autoDownloadWifi) {
                updates.updatePreferences(autoDownloadWifi = it)
            }
            UpdateToggle("Бета-версии", "Получать также предварительные версии.", prefs.allowBeta) {
                updates.updatePreferences(allowBeta = it)
            }
            Column(Modifier.padding(StravoTokens.SpaceLg), verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd)) {
                Text(state.message + (state.progress?.let { " $it%" } ?: ""), style = StravoType.Caption,
                    color = LocalStravoPalette.current.textPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (!state.busy) {
                    PencilButton("Проверить сейчас", { scope.launch { updates.check(force = true) } },
                        style = PencilButtonStyle.Secondary)
                    if (state.availableVersion != null && state.availableVersion != state.readyVersion)
                        PencilButton("Скачать ${state.availableVersion}", { scope.launch { updates.download() } },
                            subtitle = "Ручная загрузка использует текущую сеть, включая мобильную")
                    if (state.readyVersion != null)
                        PencilButton(if (installAllowed) "Установить ${state.readyVersion}" else "Разрешить установку",
                            { updates.install(context) })
                }
                Text("Источник — публичные релизы STRAVO на GitHub. Установка требует подтверждения Android.",
                    style = StravoType.Caption, color = LocalStravoPalette.current.textSecondary)
                Text("При установке VPN остановится. После обновления проверьте его восстановление или подключитесь заново.",
                    style = StravoType.Caption, color = LocalStravoPalette.current.textSecondary)
            }
        }
    }
}

/** Parent renders this once above either phone or TV root; Later is scoped to this version/stage. */
@Composable
fun AppUpdateNotice(updates: AppUpdates, modifier: Modifier = Modifier) {
    // Keep this effect and scope alive when Later hides the card; downloads must not be dismissed.
    val scope = rememberCoroutineScope()
    LaunchedEffect(updates) { updates.check(); updates.download(automatic = true) }
    val state by updates.state.collectAsStateWithLifecycle()
    val version = state.readyVersion ?: state.availableVersion ?: return
    val key = "$version:${state.readyVersion != null}"
    var dismissed by rememberSaveable(key) { mutableStateOf(false) }
    if (dismissed) return
    val context = LocalContext.current
    var installAllowed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        installAllowed = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    }
    StravoCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceSm)) {
            Text("STRAVO $version — " + if (state.readyVersion != null) "готово к установке" else "доступно обновление",
                style = StravoType.BodyStrong, color = LocalStravoPalette.current.textPrimary)
            Text("Установка требует подтверждения Android и остановит VPN. После обновления проверьте подключение.",
                style = StravoType.Caption, color = LocalStravoPalette.current.textSecondary)
            Text(state.message + (state.progress?.let { " $it%" } ?: ""), style = StravoType.Caption,
                color = LocalStravoPalette.current.textSecondary)
            if (!state.busy) {
                if (state.readyVersion != null)
                    PencilButton(if (installAllowed) "Установить" else "Разрешить установку", { updates.install(context) })
                else PencilButton("Скачать", { scope.launch { updates.download() } },
                    subtitle = "Ручная загрузка использует текущую сеть, включая мобильную")
            }
            PencilButton("Позже", { dismissed = true }, style = PencilButtonStyle.Secondary)
        }
    }
}

@Composable
private fun UpdateToggle(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) {
    StravoSettingRow(R.drawable.ic_settings, title, subtitle = subtitle, trailing = {
        StravoToggle(checked, change, Modifier.semantics { contentDescription = title })
    })
}
