package com.stravo.vpn.ui.mobile

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stravo.vpn.R
import com.stravo.vpn.data.subscription.ImportError
import com.stravo.vpn.ui.components.PencilButton
import com.stravo.vpn.ui.components.PencilButtonStyle
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.components.StravoScreenHeader
import com.stravo.vpn.ui.state.HomeEvent
import com.stravo.vpn.ui.state.HomeUiState
import com.stravo.vpn.ui.state.SubscriptionImportState
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/**
 * Добавление подписки: ссылка подписки, одиночный ключ или QR.
 * Введённая строка не сохраняется в состоянии экрана после успеха и нигде не логируется.
 */
@Composable
fun AddSubscriptionScreen(
    state: HomeUiState,
    onEvent: (HomeEvent) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    val importState = state.importState

    LaunchedEffect(importState) {
        if (importState is SubscriptionImportState.Done) input = ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = StravoTokens.ScreenPaddingMobile),
    ) {
        StravoScreenHeader(
            title = stringResource(id = R.string.add_sub_title),
            onBack = onBack,
            modifier = Modifier.padding(top = StravoTokens.SpaceMd),
        )

        Text(
            text = stringResource(id = R.string.add_sub_sub),
            style = StravoType.Caption,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = StravoTokens.SpaceSm),
        )

        if (state.subscription.isActive) {
            Spacer(modifier = Modifier.height(StravoTokens.SpaceLg))
            ActiveSubscriptionCard(state = state)
        }

        Spacer(modifier = Modifier.height(StravoTokens.SpaceLg))

        SecretField(
            value = input,
            onValueChange = { input = it },
            placeholder = stringResource(id = R.string.add_sub_field_hint),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = StravoTokens.SpaceMd),
            horizontalArrangement = Arrangement.spacedBy(StravoTokens.SpaceSm),
        ) {
            PencilButton(
                text = stringResource(id = R.string.add_sub_action_paste),
                onClick = { readClipboard(context)?.let { input = it } },
                modifier = Modifier.weight(1f),
                style = PencilButtonStyle.Secondary,
            )
            PencilButton(
                text = stringResource(id = R.string.add_sub_action_scan),
                onClick = onScan,
                modifier = Modifier.weight(1f),
                style = PencilButtonStyle.Secondary,
            )
        }

        PencilButton(
            text = stringResource(id = R.string.add_sub_action_add),
            onClick = { onEvent(HomeEvent.SubscriptionSubmitted(input)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = StravoTokens.SpaceMd),
            style = PencilButtonStyle.Primary,
        )

        importMessage(importState)?.let { message ->
            Text(
                text = message,
                style = StravoType.Caption,
                color = if (importState is SubscriptionImportState.Failed) palette.textPrimary else palette.accent,
                modifier = Modifier.padding(top = StravoTokens.SpaceMd),
            )
        }

        if (state.subscription.isActive) {
            PencilButton(
                text = stringResource(id = R.string.add_sub_remove),
                onClick = { onEvent(HomeEvent.SubscriptionRemoved) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = StravoTokens.SpaceMd),
                style = PencilButtonStyle.Secondary,
            )
        }

        Text(
            text = stringResource(id = R.string.add_sub_help),
            style = StravoType.Tiny,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = StravoTokens.SpaceLg),
        )
        Text(
            text = stringResource(id = R.string.add_sub_login_note),
            style = StravoType.Tiny,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = StravoTokens.SpaceSm),
        )
        Spacer(modifier = Modifier.height(StravoTokens.SpaceXl))
    }
}

@Composable
private fun ActiveSubscriptionCard(state: HomeUiState) {
    val palette = LocalStravoPalette.current
    StravoCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(id = R.string.add_sub_current),
                style = StravoType.Tiny,
                color = palette.textSecondary,
            )
            Text(
                text = state.subscription.planName,
                style = StravoType.BodyStrong,
                color = palette.textPrimary,
                modifier = Modifier.padding(top = StravoTokens.SpaceXs),
            )
            state.subscription.activeUntil?.let { until ->
                Text(
                    text = stringResource(id = R.string.profile_valid_until, until),
                    style = StravoType.Caption,
                    color = palette.textSecondary,
                )
            }
            if (state.subscriptionNodes.isNotEmpty()) {
                Text(
                    text = stringResource(id = R.string.add_sub_nodes, state.subscriptionNodes.size),
                    style = StravoType.Caption,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = StravoTokens.SpaceXs),
                )
            }
        }
    }
}

/** Поле для ссылки: многострочное, чтобы длинный ключ был виден целиком. */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val palette = LocalStravoPalette.current
    val shape = RoundedCornerShape(StravoTokens.CardRadiusMobile)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.panel)
            .border(1.dp, palette.outline.copy(alpha = 0.28f), shape)
            .defaultMinSize(minHeight = 96.dp)
            .padding(StravoTokens.SpaceLg),
        contentAlignment = Alignment.TopStart,
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, style = StravoType.Body, color = palette.textSecondary)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            maxLines = 4,
            textStyle = StravoType.Body.copy(color = palette.textPrimary),
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun importMessage(state: SubscriptionImportState): String? = when (state) {
    SubscriptionImportState.Idle -> null
    SubscriptionImportState.Loading -> stringResource(id = R.string.add_sub_loading)
    is SubscriptionImportState.Done -> stringResource(id = R.string.add_sub_done) +
        ". " + stringResource(id = R.string.add_sub_done_sub)
    is SubscriptionImportState.Failed -> importErrorText(state.error)
}

@Composable
private fun importErrorText(error: ImportError): String = when (error) {
    ImportError.EMPTY -> stringResource(id = R.string.add_sub_error_empty)
    ImportError.UNKNOWN_LINK -> stringResource(id = R.string.add_sub_error_unknown)
    ImportError.NETWORK -> stringResource(id = R.string.add_sub_error_network)
    ImportError.EMPTY_PAYLOAD -> stringResource(id = R.string.add_sub_error_empty_payload)
    ImportError.NO_NODES -> stringResource(id = R.string.add_sub_error_no_nodes)
    ImportError.TOO_MANY_NODES -> stringResource(id = R.string.add_sub_error_too_many)
    ImportError.SECRET_STORE -> stringResource(id = R.string.add_sub_error_secret_store)
}

private fun readClipboard(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

