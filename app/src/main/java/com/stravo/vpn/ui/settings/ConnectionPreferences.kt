package com.stravo.vpn.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stravo.vpn.R
import com.stravo.vpn.data.settings.StravoSettings
import com.stravo.vpn.engine.box.NetworkPolicy
import com.stravo.vpn.ui.components.PencilButton
import com.stravo.vpn.ui.components.PencilButtonStyle
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.components.StravoSettingRow
import com.stravo.vpn.ui.components.StravoToggle
import com.stravo.vpn.ui.components.pencilSurface
import com.stravo.vpn.ui.state.StravoViewModel
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** Shared phone/TV form. Place inside the screen's existing scrolling column. */
@Composable
fun ConnectionPreferences(viewModel: StravoViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var connectionExpanded by rememberSaveable { mutableStateOf(false) }
    var dnsExpanded by rememberSaveable { mutableStateOf(false) }
    var routesExpanded by rememberSaveable { mutableStateOf(false) }

    StravoCard(modifier = modifier.fillMaxWidth(), padding = 0.dp) {
        PreferenceHeading("Подключение", connectionExpanded) { connectionExpanded = !connectionExpanded }
        if (connectionExpanded) {
            PreferenceToggle("Подключаться при открытии", "Только после вашего разрешения на VPN.", settings.autoConnect) {
                viewModel.updateSettings { current -> current.copy(autoConnect = it) }
            }
            PreferenceToggle("Восстанавливать соединение", "Переподключаться при обрыве; ручное отключение сохраняется.", settings.autoReconnect) {
                viewModel.updateSettings { current -> current.copy(autoReconnect = it) }
            }
            PreferenceToggle("После перезагрузки устройства", "Возобновлять разрешённое подключение при запуске Android.", settings.startOnBoot) {
                viewModel.updateSettings { current -> current.copy(startOnBoot = it) }
            }
            PreferenceToggle("Заменять недоступный сервер", "При отказе выбранного сервера искать другой в той же подписке.", settings.autoFailover) {
                viewModel.updateSettings { current -> current.copy(autoFailover = it) }
            }
            ProbeEditor(settings, viewModel)
        }
        PreferenceHeading("DNS и IPv6", dnsExpanded) { dnsExpanded = !dnsExpanded }
        if (dnsExpanded) DnsEditor(settings, viewModel)
        PreferenceHeading("Правила маршрутизации", routesExpanded) { routesExpanded = !routesExpanded }
        if (routesExpanded) RouteEditor(settings, viewModel)
    }
}

@Composable
private fun PreferenceHeading(title: String, expanded: Boolean, onClick: () -> Unit) {
    StravoSettingRow(
        iconRes = R.drawable.ic_network,
        title = title,
        modifier = Modifier.semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" },
        onClick = onClick,
        trailing = {
            Text(if (expanded) "−" else "+", style = StravoType.BodyStrong, color = LocalStravoPalette.current.textPrimary)
        },
    )
}

@Composable
private fun PreferenceToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    StravoSettingRow(
        iconRes = R.drawable.ic_settings,
        title = title,
        subtitle = subtitle,
        trailing = {
            StravoToggle(checked, onChange, Modifier.semantics { contentDescription = title })
        },
    )
}

@Composable
private fun DnsEditor(settings: StravoSettings, viewModel: StravoViewModel) {
    var direct by rememberSaveable(settings.directDns) { mutableStateOf(settings.directDns) }
    var remote by rememberSaveable(settings.remoteDns) { mutableStateOf(settings.remoteDns) }
    var ipv6 by rememberSaveable(settings.ipv6Policy) { mutableStateOf(settings.ipv6Policy) }
    var feedback by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.padding(StravoTokens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd),
    ) {
        PreferenceHint("HTTPS DoH (например, https://dns.google/dns-query) или IP для UDP/53. Без логина, пароля, query-параметров и фрагмента. Имя прямого DoH сначала разрешается системным DNS.")
        PreferenceToggle("DNS из профиля", "Использовать DNS WireGuard-профиля, если он указан.",
            settings.preferProfileDns) { value ->
            viewModel.updateSettings { it.copy(preferProfileDns = value) }
        }
        AdaptiveFields(
            first = { PreferenceField("Прямой DNS", direct, { direct = it; feedback = null }) },
            second = { PreferenceField("DNS через VPN", remote, { remote = it; feedback = null }) },
        )
        PencilButton(
            text = when (ipv6) {
                "ipv4_only" -> "IPv6: только IPv4"
                "prefer_ipv6" -> "IPv6: предпочитать IPv6"
                else -> "IPv6: предпочитать IPv4"
            },
            subtitle = "Нажмите, чтобы выбрать следующий режим",
            style = PencilButtonStyle.Secondary,
            onClick = {
                val policies = NetworkPolicy.ipv6Policies
                ipv6 = policies[(policies.indexOf(ipv6) + 1) % policies.size]
                feedback = null
            },
        )
        PreferenceHint("«Только IPv4» ограничивает DNS и трафик туннеля; адрес VPN-сервера задаёт подписка.")
        PencilButton("Сохранить DNS", onClick = {
            feedback = saveSettings(viewModel) { current ->
                current.copy(directDns = direct.trim(), remoteDns = remote.trim(), ipv6Policy = ipv6)
            }
        })
        PreferenceFeedback(feedback)
    }
}

@Composable
private fun RouteEditor(settings: StravoSettings, viewModel: StravoViewModel) {
    var directDomains by rememberSaveable(settings.directDomains) { mutableStateOf(settings.directDomains.sorted().joinToString("\n")) }
    var proxyDomains by rememberSaveable(settings.proxyDomains) { mutableStateOf(settings.proxyDomains.sorted().joinToString("\n")) }
    var blockDomains by rememberSaveable(settings.blockDomains) { mutableStateOf(settings.blockDomains.sorted().joinToString("\n")) }
    var directIps by rememberSaveable(settings.directIpCidrs) { mutableStateOf(settings.directIpCidrs.sorted().joinToString("\n")) }
    var proxyIps by rememberSaveable(settings.proxyIpCidrs) { mutableStateOf(settings.proxyIpCidrs.sorted().joinToString("\n")) }
    var blockIps by rememberSaveable(settings.blockIpCidrs) { mutableStateOf(settings.blockIpCidrs.sorted().joinToString("\n")) }
    var bypassLan by rememberSaveable(settings.bypassLan) { mutableStateOf(settings.bypassLan) }
    var feedback by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.padding(StravoTokens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd),
    ) {
        PreferenceHint("По одной записи на строку, до 500 в каждом списке. example.org — точный домен; *.example.org — домен и поддомены. IP: 192.0.2.1, 192.0.2.0/24 или 2001:db8::/32.")
        PreferenceHint("При пересечении правил: блокировать → через VPN → напрямую → локальная сеть. Системные ограничения VPN сохраняются.")
        AdaptiveFields(
            first = { PreferenceField("Домены напрямую", directDomains, { directDomains = it; feedback = null }, multiline = true) },
            second = { PreferenceField("IP/CIDR напрямую", directIps, { directIps = it; feedback = null }, multiline = true) },
        )
        AdaptiveFields(
            first = { PreferenceField("Домены через VPN", proxyDomains, { proxyDomains = it; feedback = null }, multiline = true) },
            second = { PreferenceField("IP/CIDR через VPN", proxyIps, { proxyIps = it; feedback = null }, multiline = true) },
        )
        AdaptiveFields(
            first = { PreferenceField("Блокировать домены", blockDomains, { blockDomains = it; feedback = null }, multiline = true) },
            second = { PreferenceField("Блокировать IP/CIDR", blockIps, { blockIps = it; feedback = null }, multiline = true) },
        )
        PreferenceToggle("Локальная сеть напрямую", "Частные и другие непубличные адреса — вне VPN-сервера.", bypassLan) {
            bypassLan = it
            feedback = null
        }
        PencilButton("Сохранить маршруты", onClick = {
            feedback = saveSettings(viewModel) { current ->
                current.copy(
                    directDomains = NetworkPolicy.parseList(directDomains),
                    proxyDomains = NetworkPolicy.parseList(proxyDomains),
                    blockDomains = NetworkPolicy.parseList(blockDomains),
                    directIpCidrs = NetworkPolicy.parseList(directIps),
                    proxyIpCidrs = NetworkPolicy.parseList(proxyIps),
                    blockIpCidrs = NetworkPolicy.parseList(blockIps),
                    bypassLan = bypassLan,
                )
            }
        })
        PreferenceFeedback(feedback)
    }
}

@Composable
private fun ProbeEditor(settings: StravoSettings, viewModel: StravoViewModel) {
    var url by rememberSaveable(settings.probeUrl) { mutableStateOf(settings.probeUrl) }
    var timeout by rememberSaveable(settings.probeTimeoutMs) { mutableStateOf(settings.probeTimeoutMs.toString()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.padding(StravoTokens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd),
    ) {
        PreferenceHint("Адрес проверки доступности используется при подключении и явной проверке. Сохранение настроек само не выполняет запрос.")
        AdaptiveFields(
            first = { PreferenceField("HTTPS-адрес проверки", url, { url = it; feedback = null }) },
            second = {
                PreferenceField("Ожидание, мс (2000–30000)", timeout, { timeout = it; feedback = null }, keyboardType = KeyboardType.Number)
            },
        )
        PencilButton("Сохранить проверку", onClick = {
            feedback = saveSettings(viewModel) { current ->
                current.copy(probeUrl = url.trim(), probeTimeoutMs = timeout.toIntOrNull() ?: 0)
            }
        })
        PreferenceFeedback(feedback)
    }
}

/** Merge only this editor's fields into fresh state, preserving source selection and other edits. */
private fun saveSettings(viewModel: StravoViewModel, transform: (StravoSettings) -> StravoSettings): String {
    val error = NetworkPolicy.validate(transform(viewModel.settings.value))
    if (error != null) return error
    return try {
        viewModel.updateSettings(transform)
        "Сохранено. Параметры будут использованы при подключении."
    } catch (_: Exception) {
        // A parser/IO exception may contain an endpoint; never surface exception text.
        "Не удалось сохранить настройки. Повторите попытку."
    }
}

@Composable
private fun AdaptiveFields(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 620.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd)) {
                Column(Modifier.weight(1f)) { first() }
                Column(Modifier.weight(1f)) { second() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd)) {
                first()
                second()
            }
        }
    }
}

@Composable
private fun PreferenceField(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    multiline: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Uri,
) {
    val palette = LocalStravoPalette.current
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceSm)) {
        Text(title, style = StravoType.BodyStrong, color = palette.textPrimary)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            maxLines = if (multiline) 5 else 1,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = if (multiline) KeyboardType.Text else keyboardType,
            ),
            textStyle = StravoType.Body.copy(color = palette.textPrimary),
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = title }
                .pencilSurface(palette.panelSoft, if (focused) palette.accent else palette.outline,
                    StravoTokens.ButtonRadiusMobile, focused = focused, dark = palette.isDark)
                .defaultMinSize(minHeight = StravoTokens.TouchTargetMin)
                .padding(StravoTokens.SpaceMd),
        )
    }
}

@Composable
private fun PreferenceHint(text: String) {
    Text(text, style = StravoType.Caption, color = LocalStravoPalette.current.textSecondary)
}

@Composable
private fun PreferenceFeedback(message: String?) {
    if (message != null) {
        Text(message, style = StravoType.Caption, color = LocalStravoPalette.current.textPrimary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}
