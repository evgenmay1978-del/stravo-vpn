package com.stravo.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.stravo.vpn.ui.state.SubscriptionImportState
import androidx.lifecycle.ViewModelProvider
import com.stravo.vpn.platform.DeviceType
import com.stravo.vpn.ui.StravoAppRoot
import com.stravo.vpn.ui.state.StravoViewModel
import com.stravo.vpn.ui.theme.StravoTheme

class MainActivity : ComponentActivity() {
    private val stravoViewModel: StravoViewModel by lazy { ViewModelProvider(this)[StravoViewModel::class.java] }
    private var externalImport by mutableStateOf<String?>(null)

    /** Системный диалог разрешения VPN: без него ядро не получит TUN. */
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            stravoViewModel.onVpnPermissionResult(result.resultCode == RESULT_OK)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptImportIntent(intent)
        setContent {
            LaunchedEffect(stravoViewModel) {
                stravoViewModel.vpnPermissionRequests.collect { requestVpnPermissionIfNeeded() }
            }
            val formFactor = remember { DeviceType.formFactorOf(this) }
            StravoTheme(formFactor = formFactor) {
                StravoAppRoot(viewModel = stravoViewModel, formFactor = formFactor)
                val state by stravoViewModel.home.collectAsStateWithLifecycle()
                externalImport?.let { raw ->
                    val busy = state.importState is SubscriptionImportState.Loading
                    val done = state.importState is SubscriptionImportState.Done
                    fun closeImport() { externalImport = null; stravoViewModel.clearImportState() }
                    AlertDialog(
                        onDismissRequest = { if (!busy) closeImport() },
                        title = { Text("Импорт в STRAVO") },
                        text = { Text(when (val result = state.importState) {
                            SubscriptionImportState.Loading -> "Добавляем профиль…"
                            is SubscriptionImportState.Done -> "Профиль добавлен. Узлов: " + result.nodeCount
                            is SubscriptionImportState.Failed -> com.stravo.vpn.ui.mobile.importErrorText(result)
                            else -> "Добавить профиль, переданный из другого приложения?"
                        }) },
                        confirmButton = { TextButton(enabled = !busy, onClick = {
                            if (done) closeImport() else stravoViewModel.importSubscription(raw)
                        }) { Text(if (done) "Готово" else "Добавить") } },
                        dismissButton = { TextButton(enabled = !busy, onClick = { closeImport() }) { Text("Отмена") } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptImportIntent(intent)
    }

    private fun acceptImportIntent(intent: Intent?) {
        val payload = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { intent.data?.scheme == "stravo" }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 * 1024 } ?: return
        if (stravoViewModel.home.value.importState is SubscriptionImportState.Loading) return
        stravoViewModel.clearImportState()
        externalImport = payload
    }

    /**
     * Only after a connection action. Denying once does not prevent a later retry.
     */
    private fun requestVpnPermissionIfNeeded() {
        val consent: Intent? = try {
            VpnService.prepare(this)
        } catch (error: Exception) {
            stravoViewModel.onVpnPermissionResult(false)
            return
        }
        if (consent == null) {
            stravoViewModel.onVpnPermissionResult(true)
            return
        }
        try {
            vpnPermission.launch(consent)
        } catch (error: Exception) {
            stravoViewModel.onVpnPermissionResult(false)
        }
    }
}

