package com.stravo.vpn.domain.model

/** Состояние подключения. Никаких «фейковых» промежуточных значений. */
sealed interface ConnectionState {

    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    data object Connected : ConnectionState

    /** TUN is up; reachability through the selected proxy has not been confirmed yet. */
    data object Checking : ConnectionState

    /** The tunnel remains active, but the HTTPS reachability probe failed. */
    data object Degraded : ConnectionState

    data class Error(val reason: String) : ConnectionState

    val isBusy: Boolean get() = this is Connecting
    val isActive: Boolean get() = this is Connected || this is Checking || this is Degraded
}
