package com.stravo.vpn.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.stravo.vpn.R
import com.stravo.vpn.data.backup.EncryptedBackup
import com.stravo.vpn.ui.components.PencilButton
import com.stravo.vpn.ui.components.PencilButtonStyle
import com.stravo.vpn.ui.components.PencilDivider
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.components.StravoSettingRow
import com.stravo.vpn.ui.components.pencilSurface
import com.stravo.vpn.ui.state.StravoViewModel
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared phone/TV SAF UI. Parent provides public suspend ViewModel methods:
 * exportBackup(password: CharArray): ByteArray and
 * restoreBackup(payload: ByteArray, password: CharArray): Boolean.
 * Neither method should log/return exception details. Restore first calls EncryptedBackup.validate;
 * only then clear wanted intent and await actual VPN teardown, restore the validated handle and reload
 * SubscriptionRepository/SettingsRepository, enforcing the TV capability policy. Serialize mutations.
 * Export must not stop VPN. The UI owns and clears the password array after either method completes.
 */
@Composable
fun BackupPreferences(viewModel: StravoViewModel, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val palette = LocalStravoPalette.current
    var pending by remember { mutableStateOf<BackupDocument?>(null) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }

    // Pick first: no password is retained while the external document provider is open.
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        picking = false
        if (uri != null) pending = BackupDocument(uri, restore = false)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri != null) pending = BackupDocument(uri, restore = true)
    }
    val enabled = !busy && !picking && pending == null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd)) {
        StravoCard(modifier = Modifier.fillMaxWidth(), padding = 0.dp) {
            StravoSettingRow(
                iconRes = R.drawable.ic_download,
                title = stringResource(R.string.backup_export),
                subtitle = stringResource(R.string.backup_export_hint),
                modifier = Modifier.semantics { if (!enabled) disabled() },
                onClick = {
                    if (enabled) {
                        status = null
                        picking = true
                        try {
                            createDocument.launch("stravo-backup.stravobak")
                        } catch (_: Exception) {
                            picking = false
                            status = R.string.backup_picker_error
                        }
                    }
                },
            )
            PencilDivider(Modifier.fillMaxWidth().height(1.dp))
            StravoSettingRow(
                iconRes = R.drawable.ic_download,
                title = stringResource(R.string.backup_restore),
                subtitle = stringResource(R.string.backup_restore_hint),
                modifier = Modifier.semantics { if (!enabled) disabled() },
                onClick = {
                    if (enabled) {
                        status = null
                        picking = true
                        try {
                            openDocument.launch(arrayOf("*/*"))
                        } catch (_: Exception) {
                            picking = false
                            status = R.string.backup_picker_error
                        }
                    }
                },
            )
        }
        val message = if (busy) R.string.backup_busy else status
        if (message != null) {
            Text(stringResource(message), style = StravoType.Caption, color = palette.textSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }

    pending?.let { document ->
        BackupPasswordDialog(
            restore = document.restore,
            onDismiss = { pending = null },
            onConfirm = { password ->
                pending = null
                busy = true
                status = null
                // Enter try/finally immediately, so disposal also clears the owned password array.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    var payload: ByteArray? = null
                    try {
                        if (document.restore) {
                            val encrypted = withContext(Dispatchers.IO) {
                                resolver.openInputStream(document.uri)?.use { EncryptedBackup.readBounded(it) }
                                    ?: throw EncryptedBackup.BackupException()
                            }
                            payload = encrypted
                            // Once explicitly confirmed, finish commit + repository reload even if
                            // this screen leaves composition; never leave live state half-reloaded.
                            val restored = withContext(NonCancellable) {
                                viewModel.restoreBackup(encrypted, password)
                            }
                            status = if (restored) {
                                R.string.backup_restored
                            } else R.string.backup_restore_error
                        } else {
                            val encrypted = viewModel.exportBackup(password)
                            payload = encrypted
                            require(encrypted.isNotEmpty() && encrypted.size <= EncryptedBackup.MAX_FILE_BYTES)
                            withContext(Dispatchers.IO) {
                                // Only the already encrypted container ever reaches the provider.
                                val output = resolver.openOutputStream(document.uri, "wt")
                                    ?: throw EncryptedBackup.BackupException()
                                output.use { it.write(encrypted); it.flush() }
                            }
                            status = R.string.backup_exported
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        status = if (document.restore) R.string.backup_restore_error else R.string.backup_export_error
                    } finally {
                        password.fill('\u0000')
                        payload?.fill(0)
                        busy = false
                    }
                }
            },
        )
    }
}

private class BackupDocument(val uri: Uri, val restore: Boolean)

@Composable
private fun BackupPasswordDialog(restore: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    val palette = LocalStravoPalette.current
    // Never rememberSaveable: passwords must not enter saved state, bundles or disk.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    fun clear() { password = ""; confirmation = "" }
    Dialog(
        onDismissRequest = { clear(); onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        StravoCard {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd),
            ) {
                Text(
                    stringResource(if (restore) R.string.backup_restore else R.string.backup_export),
                    style = StravoType.BodyStrong, color = palette.textPrimary,
                )
                Text(
                    stringResource(if (restore) R.string.backup_replace_warning else R.string.backup_password_hint),
                    style = StravoType.Caption, color = palette.textSecondary,
                )
                BackupPasswordField(password, { password = it; invalid = false }, R.string.backup_password)
                if (!restore) {
                    BackupPasswordField(confirmation, { confirmation = it; invalid = false }, R.string.backup_confirm_password)
                }
                if (invalid) {
                    Text(stringResource(R.string.backup_password_invalid), style = StravoType.Caption, color = palette.textPrimary)
                }
                PencilButton(
                    text = stringResource(if (restore) R.string.backup_replace_confirm else R.string.backup_export),
                    onClick = {
                        if (password.length !in EncryptedBackup.MIN_PASSWORD_LENGTH..EncryptedBackup.MAX_PASSWORD_LENGTH ||
                            (!restore && password != confirmation)
                        ) {
                            invalid = true
                        } else {
                            val chars = password.toCharArray()
                            clear()
                            onConfirm(chars)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                PencilButton(
                    text = stringResource(R.string.backup_cancel),
                    onClick = { clear(); onDismiss() },
                    style = PencilButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BackupPasswordField(value: String, onChange: (String) -> Unit, label: Int) {
    val palette = LocalStravoPalette.current
    val title = stringResource(label)
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceSm)) {
        Text(title, style = StravoType.Caption, color = palette.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= EncryptedBackup.MAX_PASSWORD_LENGTH) onChange(it) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            textStyle = StravoType.Body.copy(color = palette.textPrimary),
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = title }
                .pencilSurface(palette.panel, if (focused) palette.accent else palette.outline,
                    StravoTokens.ButtonRadiusMobile, focused = focused)
                .defaultMinSize(minHeight = StravoTokens.TouchTargetMin)
                .padding(StravoTokens.SpaceMd),
        )
    }
}
