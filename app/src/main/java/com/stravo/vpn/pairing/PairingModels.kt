package com.stravo.vpn.pairing

/** Одноразовая сессия переноса подписки. В QR попадает только токен, без секретов подписки. */
data class PairingSession(
    val token: String,
    val createdAtMillis: Long,
    val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    val expiresAtMillis: Long get() = createdAtMillis + ttlMillis

    fun isExpiredAt(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis

    fun secondsLeftAt(nowMillis: Long): Long =
        ((expiresAtMillis - nowMillis) / 1000L).coerceAtLeast(0L)

    companion object {
        /** 2–5 минут по спецификации: берём 5 минут. */
        const val DEFAULT_TTL_MILLIS: Long = 5 * 60 * 1000L
    }
}

enum class PairingStatus {
    IDLE,
    WAITING,
    CLAIMED,
    IMPORTING,
    SUCCESS,
    EXPIRED,
    ERROR,
}

data class PairingState(
    val status: PairingStatus = PairingStatus.IDLE,
    val backendConfigured: Boolean = false,
    val message: String? = null,
) {
    val isTerminal: Boolean
        get() = status == PairingStatus.SUCCESS || status == PairingStatus.EXPIRED || status == PairingStatus.ERROR
}
