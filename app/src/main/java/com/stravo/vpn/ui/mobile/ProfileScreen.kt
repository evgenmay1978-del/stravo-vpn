package com.stravo.vpn.ui.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stravo.vpn.R
import com.stravo.vpn.telegram.BotLinkLauncher
import com.stravo.vpn.telegram.BotLinks
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.components.StravoScreenHeader
import com.stravo.vpn.ui.components.StravoSettingRow
import com.stravo.vpn.ui.state.HomeUiState
import com.stravo.vpn.ui.state.Notice
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType
import com.stravo.vpn.ui.components.PencilDivider

/** Профиль: подписка и сервисные действия. Секретов подписки на экране нет. */
@Composable
fun ProfileScreen(
    state: HomeUiState,
    onConnectTv: () -> Unit,
    onAddSubscription: () -> Unit,
    onRemoveSubscription: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    val context = LocalContext.current
    val notAvailable = stringResource(id = R.string.profile_section_soon)
    // Удаление подписки необратимо для ключей на устройстве — сначала спрашиваем.
    var confirmRemoval by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = StravoTokens.ScreenPaddingMobile),
    ) {
        StravoScreenHeader(
            title = stringResource(id = R.string.profile_title),
            onBack = onBack,
            modifier = Modifier.padding(top = StravoTokens.SpaceMd),
        )

        Spacer(modifier = Modifier.height(StravoTokens.SpaceLg))

        StravoCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_crown),
                    contentDescription = null,
                    tint = palette.textPrimary,
                    modifier = Modifier.size(34.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = StravoTokens.SpaceMd),
                ) {
                    Text(
                        text = subscriptionTitle(state),
                        style = StravoType.BodyStrong,
                        color = palette.textPrimary,
                    )
                    Text(
                        text = subscriptionSubtitle(state),
                        style = StravoType.Caption,
                        color = palette.textSecondary,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_chevron),
                        contentDescription = null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    if (state.subscription.isActive) {
                        Text(
                            text = stringResource(id = R.string.profile_active),
                            style = StravoType.Tiny,
                            color = palette.accent,
                            modifier = Modifier.padding(top = StravoTokens.SpaceXs),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceLg))

        StravoCard(modifier = Modifier.fillMaxWidth(), padding = 0.dp) {
            StravoSettingRow(
                iconRes = R.drawable.ic_settings,
                title = stringResource(id = R.string.profile_manage),
                subtitle = if (state.subscription.isActive) {
                    stringResource(id = R.string.profile_manage_active)
                } else {
                    stringResource(id = R.string.profile_manage_none)
                },
                onClick = onAddSubscription,
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_profile,
                title = stringResource(id = R.string.profile_add_subscription),
                subtitle = stringResource(id = R.string.profile_add_subscription_sub),
                onClick = onAddSubscription,
            )
            if (state.subscription.isActive) {
                PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
                StravoSettingRow(
                    iconRes = R.drawable.ic_delete,
                    title = stringResource(id = R.string.profile_remove),
                    subtitle = stringResource(id = R.string.profile_remove_sub),
                    onClick = { confirmRemoval = true },
                )
            }
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_tv,
                title = stringResource(id = R.string.profile_connect_tv),
                subtitle = stringResource(id = R.string.profile_connect_tv_sub),
                onClick = onConnectTv,
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_share,
                title = stringResource(id = R.string.profile_invite),
                subtitle = notAvailable,
                onClick = { },
            )
            PencilDivider(modifier = Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_send,
                title = stringResource(id = R.string.profile_support),
                subtitle = BotLinks.supportHandle(),
                onClick = {
                    BotLinkLauncher.openUrl(
                        context = context,
                        primary = "tg://resolve?domain=" + com.stravo.vpn.core.StravoConfig.BOT_USERNAME,
                        fallback = BotLinks.supportHttps(),
                    )
                },
            )
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))
        Text(
            text = stringResource(id = R.string.profile_security_note),
            style = StravoType.Tiny,
            color = palette.textSecondary,
        )
        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))
    }

    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = {
                Text(
                    text = stringResource(id = R.string.profile_remove_title),
                    style = StravoType.BodyStrong,
                    color = palette.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(id = R.string.profile_remove_body),
                    style = StravoType.Caption,
                    color = palette.textSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoval = false
                        onRemoveSubscription()
                    },
                ) {
                    Text(
                        text = stringResource(id = R.string.profile_remove_confirm),
                        color = palette.accent,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoval = false }) {
                    Text(
                        text = stringResource(id = R.string.profile_remove_cancel),
                        color = palette.textSecondary,
                    )
                }
            },
        )
    }
}

@Composable
private fun subscriptionTitle(state: HomeUiState): String =
    if (state.subscription.isActive) state.subscription.planName else stringResource(id = R.string.profile_no_subscription)

@Composable
private fun subscriptionSubtitle(state: HomeUiState): String {
    val until = state.subscription.activeUntil
    return if (state.subscription.isActive && until != null) {
        stringResource(id = R.string.profile_valid_until, until)
    } else {
        stringResource(id = R.string.profile_connect_hint)
    }
}

/** Напоминание о том, что секции подписки ждут серверную часть. */
@Composable
fun profileNoticeText(): String = stringResource(id = R.string.notice_profile_missing)

internal val profileNotice = Notice.PROFILE_MISSING
