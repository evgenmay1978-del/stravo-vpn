package com.stravo.vpn.domain.engine

import com.stravo.vpn.domain.model.ConnectionState
import com.stravo.vpn.domain.model.VpnLocation
import com.stravo.vpn.domain.model.VpnProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Честная заглушка: в этой сборке настоящего ядра туннеля нет.
 *
 * Приложение НЕ имитирует подключение таймером: попытка соединения сразу возвращает
 * понятную ошибку, а состояние «ГОТОВО К ПОДКЛЮЧЕНИЮ» остаётся исходным.
 * Что именно нужно от серверной стороны — описано в docs/IMPLEMENTATION.md.
 */
class UnavailableVpnEngine(
    private val reason: String = MISSING_CORE_REASON,
) : VpnEngine {

    private val state = MutableStateFlow(VpnConnectionSnapshot())

    override fun observeState(): StateFlow<VpnConnectionSnapshot> = state.asStateFlow()

    override suspend fun connect(profile: VpnProfile?, location: VpnLocation) {
        state.update { current ->
            current.copy(
                state = ConnectionState.Error(reason),
                locationId = location.id,
                connectedSince = null,
            )
        }
    }

    override suspend fun disconnect() {
        state.update { VpnConnectionSnapshot() }
    }

    companion object {
        const val MISSING_CORE_REASON: String =
            "Ядро туннеля не подключено в этой сборке: нужен VpnService и контракт подписки"
    }
}
