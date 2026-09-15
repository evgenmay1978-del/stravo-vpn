package com.stravo.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.stravo.vpn.platform.detectFormFactor
import com.stravo.vpn.ui.navigation.StravoNavGraph
import com.stravo.vpn.ui.theme.StravoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val formFactor = detectFormFactor()
        setContent {
            StravoTheme {
                StravoNavGraph(formFactor = formFactor)
            }
        }
    }
}
