package com.stravo.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stravo.vpn.platform.DeviceType
import com.stravo.vpn.ui.StravoAppRoot
import com.stravo.vpn.ui.state.StravoViewModel
import com.stravo.vpn.ui.theme.StravoTheme

class MainActivity : ComponentActivity() {

    /** Системный диалог разрешения VPN: без него ядро не получит TUN. */
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestVpnPermissionIfNeeded()
        setContent {
            val formFactor = remember { DeviceType.formFactorOf(this) }
            StravoTheme(formFactor = formFactor) {
                val viewModel: StravoViewModel = viewModel()
                StravoAppRoot(viewModel = viewModel, formFactor = formFactor)
            }
        }
    }

    /**
     * Разрешение спрашиваем один раз при первом запуске. Если пользователь отказал,
     * ядро честно скажет об этом при попытке подключения — «подключено» не рисуется.
     */
    private fun requestVpnPermissionIfNeeded() {
        val consent: Intent? = try {
            VpnService.prepare(this)
        } catch (error: Exception) {
            null
        }
        if (consent == null) return
        try {
            vpnPermission.launch(consent)
        } catch (error: Exception) {
            // Диалог недоступен: остаётся честная ошибка от ядра.
        }
    }
}

