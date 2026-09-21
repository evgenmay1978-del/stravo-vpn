package com.stravo.vpn.ui.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stravo.vpn.domain.policy.CapabilityPolicy
import com.stravo.vpn.domain.subscription.SubscriptionService
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.components.SubscriptionDetails
import com.stravo.vpn.ui.components.pencilSurface
import com.stravo.vpn.ui.state.HomeUiState
import com.stravo.vpn.ui.state.StravoViewModel
import com.stravo.vpn.ui.state.SubscriptionRefreshState
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** Same management actions on phone and TV. No subscription URL enters the model. */
@Composable
fun SubscriptionManager(
    state: HomeUiState,
    viewModel: StravoViewModel,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalStravoPalette.current
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var interval by rememberSaveable { mutableStateOf("6") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var replaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var replacement by remember { mutableStateOf("") }
    val sources = state.subscriptionSources.filter { CapabilityPolicy.permits(it.service, state.formFactor) }
    AlertDialog(
        modifier = Modifier.pencilSurface(palette.panel, palette.outline, StravoTokens.CardRadiusMobile),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        onDismissRequest = onDismiss,
        title = { Text("Подписки", style = StravoType.BodyStrong) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd)) {
                if (sources.isEmpty()) Text("Добавьте подписку ссылкой или войдите по Maestro login.")
                sources.forEach { source ->
                    val selected = state.selectedSourceId == source.id
                    StravoCard(Modifier.fillMaxWidth(), padding = StravoTokens.SpaceMd) {
                        Text(source.name, style = StravoType.BodyStrong, color = palette.textPrimary)
                        Text((if (source.service == SubscriptionService.CDN) "CDN" else "Обычный VPN") +
                            " · " + state.subscriptionNodes.count { it.sourceId == source.id } + " узлов" +
                            if (!source.enabled) " · отключена" else if (selected) " · выбрана" else "",
                            style = StravoType.Caption, color = palette.textSecondary)
                        source.subscription.activeUntil?.let {
                            Text("Действует до " + it, style = StravoType.Caption)
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { replaceId = source.id; replacement = "" }) { Text("Заменить ссылку") }
                            TextButton(enabled = source.enabled && !state.connection.isBusy,
                                onClick = { onSelect(source.id) }) { Text(if (selected) "Выбрана" else "Выбрать") }
                            TextButton(enabled = state.subscriptionRefresh != SubscriptionRefreshState.LOADING,
                                onClick = { viewModel.refreshSource(source.id) }) { Text("Обновить") }
                            TextButton(onClick = {
                                editId = source.id
                                name = source.name
                                interval = source.updateIntervalHours.toString()
                            }) { Text("Настроить") }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { viewModel.setSourceEnabled(source.id, !source.enabled) }) {
                                Text(if (source.enabled) "Отключить подписку" else "Включить подписку")
                            }
                            TextButton(onClick = { deleteId = source.id }) { Text("Удалить") }
                        }
                        if (selected) SubscriptionDetails(source.subscription)
                    }
                }
                TextButton(enabled = state.subscriptionRefresh != SubscriptionRefreshState.LOADING,
                    onClick = viewModel::refreshSubscriptions) {
                    Text(if (state.subscriptionRefresh == SubscriptionRefreshState.LOADING) "Обновляем…" else "Обновить все")
                }
                val feedback = when (state.subscriptionRefresh) {
                    SubscriptionRefreshState.UPDATED -> "Подписка обновлена"
                    SubscriptionRefreshState.FAILED -> "Обновление не завершено. Прежние данные сохранены"
                    SubscriptionRefreshState.NO_SOURCE -> "Это локальный профиль без ссылки обновления"
                    else -> null
                }
                feedback?.let { Text(it, style = StravoType.Caption) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onAdd) { Text("Добавить / логин") } },
    )
    editId?.let { id ->
        AlertDialog(
            onDismissRequest = { editId = null },
            title = { Text("Настройки подписки") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(128) },
                        label = { Text("Название") }, singleLine = true)
                    OutlinedTextField(value = interval,
                        onValueChange = { interval = it.filter(Char::isDigit).take(4) },
                        label = { Text("Обновлять каждые N часов; 0 — вручную") }, singleLine = true)
                    Text("Android может отложить фоновое обновление для экономии батареи.",
                        style = StravoType.Tiny)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && interval.toIntOrNull()?.let { it in 0..8760 } == true,
                    onClick = {
                        viewModel.editSource(id, name.trim(), interval.toInt())
                        editId = null
                    }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editId = null }) { Text("Отмена") } },
        )
    }
    replaceId?.let { id ->
        AlertDialog(onDismissRequest = { replaceId = null; replacement = "" },
            title = { Text("Новая ссылка подписки") },
            text = {
                OutlinedTextField(value = replacement, onValueChange = { replacement = it.take(16_384) },
                    label = { Text("Вставьте новую ссылку") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true)
            },
            confirmButton = { TextButton(enabled = replacement.isNotBlank(),
                onClick = { viewModel.replaceSource(id, replacement); replacement = ""; replaceId = null }) { Text("Заменить") } },
            dismissButton = { TextButton(onClick = { replacement = ""; replaceId = null }) { Text("Отмена") } })
    }
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null },
            title = { Text("Удалить подписку?") },
            text = { Text("Её узлы и сохранённая ссылка будут удалены с этого устройства. Остальные подписки сохранятся.") },
            confirmButton = { TextButton(onClick = { viewModel.removeSource(id); deleteId = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Отмена") } })
    }
}
