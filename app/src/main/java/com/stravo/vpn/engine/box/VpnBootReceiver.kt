package com.stravo.vpn.engine.box

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.stravo.vpn.StravoApplication

/** Runs after unlock only. Never requests permission or starts a previously stopped VPN. */
class VpnBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = (context.applicationContext as StravoApplication).container.settings.settings.value
        if (!settings.startOnBoot || !TunnelSession(context).wanted || VpnService.prepare(context) != null) return
        runCatching {
            ContextCompat.startForegroundService(context,
                Intent(context, StravoVpnService::class.java).setAction(StravoVpnService.ACTION_RESUME))
        }
    }
}
