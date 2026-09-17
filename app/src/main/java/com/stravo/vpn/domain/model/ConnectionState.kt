package com.stravo.vpn.domain.model

/** Состояние подключения. Никаких «фейковых» промежуточных значений. */
sealed interface ConnectionState {

    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    data object Connected : ConnectionState

    data class Error(val reason: String) : ConnectionState

    val isBusy: Boolean get() = this is Connecting
    val isActive: Boolean get() = this is Connected
}
