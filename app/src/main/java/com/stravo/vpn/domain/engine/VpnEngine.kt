package com.stravo.vpn.domain.engine

import com.stravo.vpn.domain.model.ConnectionState
import com.stravo.vpn.domain.model.VpnLocation
import com.stravo.vpn.domain.model.VpnProfile
import kotlinx.coroutines.flow.StateFlow

data class VpnConnectionSnapshot(
    val state: ConnectionState = ConnectionState.Disconnected,
    val locationId: String? = null,
    val connectedSince: Long? = null,
)

/**
 * Контракт туннеля. UI и ViewModel работают только с этим интерфейсом и никогда
 * не обращаются к сервису напрямую.
 */
interface VpnEngine {

    fun observeState(): StateFlow<VpnConnectionSnapshot>

    suspend fun connect(profile: VpnProfile?, location: VpnLocation)

    suspend fun disconnect()
}
