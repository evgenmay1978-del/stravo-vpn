package com.stravo.vpn.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stravo.vpn.R
import com.stravo.vpn.domain.model.NetworkMode
import com.stravo.vpn.domain.policy.CapabilityPolicy
import com.stravo.vpn.domain.subscription.SubscriptionService
import com.stravo.vpn.ui.components.PencilDivider
import com.stravo.vpn.ui.components.StravoSearchField
import com.stravo.vpn.ui.components.pencilSurface
import com.stravo.vpn.ui.state.HomeUiState
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoType

/** Browsing a source does not reconnect. A server tap commits source + node together. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickServerPicker(
    state: HomeUiState,
    onSelect: (sourceId: String, locationId: String) -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalStravoPalette.current
    val content: @Composable ColumnScope.() -> Unit = {
        QuickServerList(state, onSelect, onManage, onAdd, onDismiss)
    }
    if (state.isTv) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(0.86f).fillMaxHeight(0.88f)
                .pencilSurface(palette.panel, palette.outline, 24.dp).padding(16.dp)) { content() }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.panel,
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.86f)
                .pencilSurface(palette.panel, palette.outline, 24.dp).padding(horizontal = 16.dp)) { content() }
        }
    }
}

@Composable
private fun ColumnScope.QuickServerList(
    state: HomeUiState,
    onSelect: (String, String) -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalStravoPalette.current
    val sources = state.subscriptionSources.filter {
        it.enabled && CapabilityPolicy.permits(it.service, state.formFactor)
    }
    var browsedSourceId by rememberSaveable { mutableStateOf(state.selectedSourceId) }
    val source = sources.firstOrNull { it.id == browsedSourceId } ?: sources.firstOrNull()
    var query by rememberSaveable(source?.id) { mutableStateOf("") }
    val sourceState = state.copy(
        selectedSourceId = source?.id.orEmpty(),
        mode = if (source?.service == SubscriptionService.CDN) NetworkMode.FREE_INTERNET else NetworkMode.NORMAL_VPN,
    )
    val locations = sourceState.locations.filter {
        sourceState.availableNodes.isNotEmpty() &&
            (query.isBlank() || (it.country + " " + it.subtitle).contains(query, ignoreCase = true))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Выбрать сервер", style = StravoType.BodyStrong, color = palette.textPrimary,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp))
        TextButton(onClick = onDismiss) { Text("Закрыть") }
    }
    if (sources.isNotEmpty()) {
        val tabs = rememberLazyListState(initialFirstVisibleItemIndex =
            sources.indexOfFirst { it.id == source?.id }.coerceAtLeast(0))
        LazyRow(state = tabs, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources, key = { it.id }) { item ->
                FilterChip(
                    text = item.name + if (state.isTv) "" else
                        if (item.service == SubscriptionService.CDN) " · CDN" else " · VPN",
                    selected = item.id == source?.id,
                    onClick = { browsedSourceId = item.id },
                )
            }
        }
        StravoSearchField(value = query, onValueChange = { query = it },
            placeholder = stringResource(R.string.locations_search_hint),
            modifier = Modifier.padding(vertical = 12.dp))
    }
    key(source?.id) {
        val selectedIndex = locations.indexOfFirst { source?.id == state.selectedSourceId && it.id == state.location.id }
        val list = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceAtLeast(0))
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (locations.isEmpty()) {
                item {
                    Text(if (sources.isEmpty()) "Войдите по логину или добавьте подписку."
                        else if (sourceState.availableNodes.isEmpty()) "В подписке пока нет доступных серверов. Обновите её в управлении подписками."
                        else "Серверы не найдены. Измените поиск.",
                        style = StravoType.Body, color = palette.textSecondary, modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            items(locations, key = { it.id }) { location ->
                val selected = source?.id == state.selectedSourceId && location.id == state.location.id
                LocationRow(location = location, selected = selected,
                    pingMs = if (source?.id == state.selectedSourceId) state.measuredNodePings[location.id] else null,
                    onClick = { source?.let { onSelect(it.id, location.id) } })
                PencilDivider(Modifier.fillMaxWidth().height(1.dp))
            }
        }
    }
    Text(if (state.connection.isActive || state.connection.isBusy)
        "Нажмите сервер — VPN переключится автоматически."
        else "Выберите сервер, затем нажмите кнопку подключения.",
        style = StravoType.Caption, color = palette.textSecondary, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onManage) { Text("Управление") }
        TextButton(onClick = onAdd) { Text("Добавить / логин") }
    }
}
