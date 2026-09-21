package com.stravo.vpn.engine.box

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.stravo.vpn.domain.engine.VpnConfigProvider
import com.stravo.vpn.domain.engine.VpnConnectionSnapshot
import com.stravo.vpn.domain.engine.VpnEngine
import com.stravo.vpn.domain.model.ConnectionState
import com.stravo.vpn.domain.model.VpnLocation
import com.stravo.vpn.domain.model.VpnProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * Настоящее ядро: поднимает [StravoVpnService] с sing-box (libbox).
 *
 * Конфиг узла не проходит через движок: сервис сам забирает его из защищённого
 * хранилища по обезличенному идентификатору. Здесь только состояние и команды.
 */
class SingBoxVpnEngine(
    private val context: Context,
    private val configProvider: VpnConfigProvider,
) : VpnEngine {

    override fun observeState(): StateFlow<VpnConnectionSnapshot> = StravoVpnService.state

    override suspend fun connect(profile: VpnProfile?, location: VpnLocation, automatic: Boolean) {
        val nodeId = profile?.id
        if (nodeId.isNullOrBlank() || configProvider.configFor(nodeId) == null) {
            fail(NOTHING_TO_CONNECT, location.id)
            return
        }
        if (VpnService.prepare(context) != null) {
            fail(NO_PERMISSION, location.id)
            return
        }
        StravoVpnService.publish(
            VpnConnectionSnapshot(state = ConnectionState.Connecting, locationId = location.id),
        )
        val intent = Intent(context, StravoVpnService::class.java)
            .setAction(StravoVpnService.ACTION_START)
            .putExtra(StravoVpnService.EXTRA_NODE_ID, nodeId)
            .putExtra(StravoVpnService.EXTRA_AUTOMATIC, automatic)
            .putExtra(StravoVpnService.EXTRA_LOCATION_ID, location.id)
            // Подпись узла для журнала: «Германия · VLESS · TCP · Reality». Без хостов и ключей.
            .putExtra(StravoVpnService.EXTRA_LOCATION_LABEL, location.subtitle)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (error: Exception) {
            fail(START_FAILED, location.id)
        }
    }

    override suspend fun disconnect() {
        TunnelSession(context).stop()
        val intent = Intent(context, StravoVpnService::class.java).setAction(StravoVpnService.ACTION_STOP)
        try {
            context.startService(intent)
        } catch (error: Exception) {
            context.stopService(Intent(context, StravoVpnService::class.java))
            StravoVpnService.publish(VpnConnectionSnapshot())
        }
    }

    private fun fail(reason: String, locationId: String) {
        StravoVpnService.publish(
            VpnConnectionSnapshot(
                state = ConnectionState.Error(reason),
                locationId = locationId,
            ),
        )
    }

    companion object {
        const val NOTHING_TO_CONNECT = "Нет ключа узла: добавьте подписку и выберите локацию"
        const val NO_PERMISSION = "Нет разрешения на VPN: подтвердите подключение в системном диалоге"
        const val START_FAILED = "Не удалось запустить VPN-сервис"
    }
}

