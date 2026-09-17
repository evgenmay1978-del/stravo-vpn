package com.stravo.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stravo.vpn.platform.DeviceType
import com.stravo.vpn.ui.StravoAppRoot
import com.stravo.vpn.ui.state.StravoViewModel
import com.stravo.vpn.ui.theme.StravoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val formFactor = remember { DeviceType.formFactorOf(this) }
            StravoTheme(formFactor = formFactor) {
                val viewModel: StravoViewModel = viewModel()
                StravoAppRoot(viewModel = viewModel, formFactor = formFactor)
            }
        }
    }
}
