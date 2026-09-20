package com.stravo.vpn.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.stravo.vpn.R
import com.stravo.vpn.domain.model.NetworkMode
import com.stravo.vpn.domain.policy.CapabilityPolicy
import com.stravo.vpn.domain.subscription.SubscriptionService
import com.stravo.vpn.ui.state.HomeUiState
import com.stravo.vpn.ui.state.SubscriptionImportState
import com.stravo.vpn.ui.state.SubscriptionRefreshState
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** Выбор только добавленных подписок; сам по себе не запускает VPN. */
@Composable
fun SubscriptionPicker(
    state: HomeUiState,
    onSelect: (NetworkMode) -> Unit,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalStravoPalette.current
    val services = listOf(SubscriptionService.ORDINARY, SubscriptionService.CDN).filter { service ->
        CapabilityPolicy.permits(service, state.formFactor) &&
            state.subscriptionNodes.any { it.service == service }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.background,
        title = { Text(stringResource(R.string.subscription_picker_title), style = StravoType.BodyStrong) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd),
            ) {
                Text(
                    stringResource(
                        if (services.isEmpty()) R.string.subscription_picker_empty
                        else R.string.subscription_picker_hint,
                    ),
                    style = StravoType.Caption,
                    color = palette.textSecondary,
                )
                services.forEach { service ->
                    val mode = if (service == SubscriptionService.CDN) NetworkMode.FREE_INTERNET
                        else NetworkMode.NORMAL_VPN
                    val selected = state.mode == mode
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .selectable(
                                selected = selected,
                                enabled = !state.connection.isBusy,
                                role = Role.RadioButton,
                                onClick = { onSelect(mode) },
                            )
                            .padding(vertical = StravoTokens.SpaceMd),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null, enabled = !state.connection.isBusy)
                        Column(Modifier.weight(1f).padding(start = StravoTokens.SpaceSm)) {
                            Text(
                                stringResource(if (service == SubscriptionService.CDN)
                                    R.string.subscription_cdn_source else R.string.subscription_ordinary_source),
                                style = StravoType.BodyStrong,
                                color = palette.textPrimary,
                            )
                            Text(
                                stringResource(R.string.subscription_picker_nodes,
                                    state.subscriptionNodes.count { it.service == service }),
                                style = StravoType.Caption,
                                color = palette.textSecondary,
                            )
                        }
                    }
                }
                if (services.isNotEmpty()) {
                    TextButton(
                        enabled = state.subscriptionRefresh != SubscriptionRefreshState.LOADING &&
                            state.importState !is SubscriptionImportState.Loading,
                        onClick = onRefresh,
                    ) {
                        Text(stringResource(if (state.subscriptionRefresh == SubscriptionRefreshState.LOADING)
                            R.string.subscription_refresh_running else R.string.subscription_refresh_action))
                    }
                    val result = when (state.subscriptionRefresh) {
                        SubscriptionRefreshState.UPDATED -> R.string.subscription_refresh_done
                        SubscriptionRefreshState.FAILED -> R.string.subscription_refresh_failed
                        SubscriptionRefreshState.NO_SOURCE -> R.string.subscription_refresh_no_source
                        else -> null
                    }
                    result?.let { Text(stringResource(it), style = StravoType.Caption) }
                }
                if (state.connection.isActive) {
                    Text(stringResource(R.string.subscription_picker_disconnect), style = StravoType.Caption)
                }
                if (services.contains(SubscriptionService.CDN)) {
                    Text(stringResource(R.string.subscription_services_intro),
                        style = StravoType.Tiny, color = palette.textSecondary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.subscription_picker_close)) }
        },
        dismissButton = {
            TextButton(onClick = onAdd) { Text(stringResource(R.string.profile_add_subscription)) }
        },
    )
}
