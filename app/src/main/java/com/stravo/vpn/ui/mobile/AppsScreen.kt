package com.stravo.vpn.ui.mobile

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.stravo.vpn.ui.components.PencilIcon as Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stravo.vpn.R
import com.stravo.vpn.data.settings.VpnAppMode
import com.stravo.vpn.ui.components.PencilDivider
import com.stravo.vpn.ui.components.pencilSurface
import com.stravo.vpn.ui.components.StravoScreenHeader
import com.stravo.vpn.ui.components.StravoSearchField
import com.stravo.vpn.ui.state.StravoViewModel
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Приложение в списке раздельного туннеля: только имя, пакет и иконка. */
data class TunnelApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val system: Boolean,
)

/**
 * Раздельный туннель: какие приложения ходят через VPN.
 * «Все» — как раньше; «Только выбранные» и «Все, кроме выбранных» — список.
 */
@Composable
fun AppsScreen(
    viewModel: StravoViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }

    var loaded by remember { mutableStateOf<List<TunnelApp>?>(null) }
    LaunchedEffect(Unit) {
        loaded = withContext(Dispatchers.IO) { loadApps(context) }
    }
    val allApps = loaded.orEmpty()
    val visible = remember(query, showSystem, allApps) {
        allApps.filter { app ->
            (showSystem || !app.system) &&
                (query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = StravoTokens.ScreenPaddingMobile),
    ) {
        StravoScreenHeader(
            title = stringResource(id = R.string.settings_apps),
            onBack = onBack,
            modifier = Modifier.padding(top = StravoTokens.SpaceMd),
        )

        Text(
            text = stringResource(id = R.string.settings_apps_hint),
            style = StravoType.Caption,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = StravoTokens.SpaceSm),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = StravoTokens.SpaceLg),
            horizontalArrangement = Arrangement.spacedBy(StravoTokens.SpaceSm),
        ) {
            ModeChip(
                text = stringResource(id = R.string.apps_mode_all),
                selected = settings.appMode == VpnAppMode.ALL,
                onClick = { viewModel.updateSettings { it.copy(appMode = VpnAppMode.ALL) } },
                modifier = Modifier.weight(1f),
            )
            ModeChip(
                text = stringResource(id = R.string.apps_mode_only),
                selected = settings.appMode == VpnAppMode.ONLY_SELECTED,
                onClick = { viewModel.updateSettings { it.copy(appMode = VpnAppMode.ONLY_SELECTED) } },
                modifier = Modifier.weight(1f),
            )
            ModeChip(
                text = stringResource(id = R.string.apps_mode_except),
                selected = settings.appMode == VpnAppMode.EXCEPT_SELECTED,
                onClick = { viewModel.updateSettings { it.copy(appMode = VpnAppMode.EXCEPT_SELECTED) } },
                modifier = Modifier.weight(1f),
            )
        }

        StravoSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(id = R.string.apps_search),
            modifier = Modifier.padding(top = StravoTokens.SpaceMd),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = StravoTokens.SpaceSm)
                .clickable(role = Role.Checkbox) { showSystem = !showSystem },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(id = if (showSystem) R.drawable.ic_check else R.drawable.ic_chevron),
                contentDescription = null,
                tint = if (showSystem) palette.accent else palette.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(id = R.string.apps_show_system),
                style = StravoType.Caption,
                color = palette.textSecondary,
                modifier = Modifier.padding(start = StravoTokens.SpaceSm),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = StravoTokens.SpaceSm),
        ) {
            items(items = visible, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    selected = app.packageName in settings.apps,
                    enabled = settings.appMode != VpnAppMode.ALL,
                    onToggle = {
                        val mode = if (settings.appMode == VpnAppMode.ALL) {
                            VpnAppMode.ONLY_SELECTED
                        } else {
                            settings.appMode
                        }
                        val apps = if (app.packageName in settings.apps) {
                            settings.apps - app.packageName
                        } else {
                            settings.apps + app.packageName
                        }
                        viewModel.updateSettings { it.copy(appMode = mode, apps = apps) }
                    },
                )
                PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            }
        }

        if (loaded == null) {
            Text(
                text = stringResource(id = R.string.apps_loading),
                style = StravoType.Caption,
                color = palette.textSecondary,
                modifier = Modifier.padding(vertical = StravoTokens.SpaceMd),
            )
        }
        Spacer(modifier = Modifier.height(StravoTokens.SpaceMd))
    }
}

@Composable
private fun AppRow(
    app: TunnelApp,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val palette = LocalStravoPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(vertical = StravoTokens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val icon = app.icon
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_network),
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = StravoTokens.SpaceMd),
        ) {
            Text(
                text = app.label,
                style = StravoType.BodyStrong,
                color = if (enabled) palette.textPrimary else palette.textSecondary,
            )
            Text(
                text = app.packageName,
                style = StravoType.Tiny,
                color = palette.textSecondary,
                maxLines = 1,
            )
        }
        CheckMark(checked = selected, active = enabled)
    }
}

@Composable
private fun CheckMark(checked: Boolean, active: Boolean) {
    val palette = LocalStravoPalette.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(shape)
            .pencilSurface(if (checked) palette.accent.copy(alpha = if (active) 1f else 0.4f) else palette.panel,
                palette.outline, 8.dp, dark = checked),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                painter = painterResource(id = R.drawable.ic_check),
                contentDescription = null,
                tint = palette.onAccent,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ModeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    val shape = RoundedCornerShape(StravoTokens.ButtonRadiusMobile)
    Box(
        modifier = modifier
            .clip(shape)
            .pencilSurface(if (selected) palette.medallion else palette.panel, palette.outline,
                StravoTokens.ButtonRadiusMobile, dark = selected)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = StravoTokens.SpaceSm, horizontal = StravoTokens.SpaceSm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = StravoType.Caption,
            color = if (selected) palette.onAccent else palette.textPrimary,
            maxLines = 2,
        )
    }
}

/** Установленные приложения: имя, пакет и иконка. Системные — по желанию. */
private fun loadApps(context: Context): List<TunnelApp> {
    val manager = context.packageManager
    val result = ArrayList<TunnelApp>()
    val installed = try {
        manager.getInstalledApplications(0)
    } catch (error: Exception) {
        emptyList<ApplicationInfo>()
    }
    for (info in installed) {
        val label = try {
            manager.getApplicationLabel(info).toString()
        } catch (error: Exception) {
            info.packageName
        }
        val icon = try {
            manager.getApplicationIcon(info.packageName).toBitmap(72, 72).asImageBitmap()
        } catch (error: Throwable) {
            null
        }
        result.add(
            TunnelApp(
                packageName = info.packageName,
                label = label,
                icon = icon,
                system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            ),
        )
    }
    return result.sortedBy { it.label.lowercase() }
}

