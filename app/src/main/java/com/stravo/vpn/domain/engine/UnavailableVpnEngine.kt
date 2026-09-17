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
 *
 * Если конфиг узла уже разобран и лежит в защищённом хранилище, текст ошибки это
 * учитывает: ядру осталось только забрать ключ. Что именно нужно от ядра —
 * описано в docs/IMPLEMENTATION.md.
 */
class UnavailableVpnEngine(
    private val configProvider: VpnConfigProvider? = null,
    private val reason: String = MISSING_CORE_REASON,
) : VpnEngine {

    private val state = MutableStateFlow(VpnConnectionSnapshot())

    override fun observeState(): StateFlow<VpnConnectionSnapshot> = state.asStateFlow()

    override suspend fun connect(profile: VpnProfile?, location: VpnLocation) {
        state.update { current ->
            current.copy(
                state = ConnectionState.Error(reasonFor(profile)),
                locationId = location.id,
                connectedSince = null,
            )
        }
    }

    override suspend fun disconnect() {
        state.update { VpnConnectionSnapshot() }
    }

    private fun reasonFor(profile: VpnProfile?): String {
        val hasConfig = profile != null && configProvider?.configFor(profile.id) != null
        return if (hasConfig) CONFIG_READY_REASON else reason
    }

    companion object {
        const val MISSING_CORE_REASON: String =
            "Ядро туннеля не подключено в этой сборке: нужен VpnService и контракт подписки"

        /** Нативная часть ядра есть в сборке, но не загрузилась на этом устройстве. */
        const val NATIVE_FAILED_REASON: String =
            "Ядро туннеля не загрузилось на этом устройстве: проверьте разрядность сборки (arm64/arm)"

        const val CONFIG_READY_REASON: String =
            "Ядро туннеля не подключено в этой сборке: ключ узла разобран и лежит в защищённом хранилище"
    }
}

