package com.stravo.vpn.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * Управляет одноразовой сессией переноса подписки.
 *
 * Локально клиент всегда может создать токен и показать QR (ссылка ведёт в Telegram-бота),
 * но статус подтверждения приходит ТОЛЬКО с сервера. Пока серверной части нет,
 * состояние остаётся WAITING с флагом backendConfigured = false — без фальшивого успеха.
 */
class PairingRepository(
    private val backend: PairingBackend = StubPairingBackend(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokenFactory: () -> String = ::randomToken,
) {

    private val _state = MutableStateFlow(PairingState())
    private var session: PairingSession? = null

    val state: StateFlow<PairingState> = _state.asStateFlow()

    fun currentSession(): PairingSession? = session

    suspend fun start(): PairingSession {
        val created = PairingSession(token = tokenFactory(), createdAtMillis = clock())
        session = created
        _state.value = PairingState(status = PairingStatus.WAITING, backendConfigured = false)
        when (val reply = backend.createSession(created.token)) {
            is BackendReply.Ok -> _state.value = PairingState(
                status = reply.status,
                backendConfigured = true,
            )

            BackendReply.NotConfigured -> _state.value = PairingState(
                status = PairingStatus.WAITING,
                backendConfigured = false,
            )

            is BackendReply.Failure -> _state.value = PairingState(
                status = PairingStatus.ERROR,
                backendConfigured = true,
                message = reply.reason,
            )
        }
        return created
    }

    /** Истёк ли текущий токен. Проверяется на каждом тике таймера. */
    fun refreshExpiry(nowMillis: Long = clock()) {
        val current = session ?: return
        if (current.isExpiredAt(nowMillis) && _state.value.status == PairingStatus.WAITING) {
            _state.value = _state.value.copy(status = PairingStatus.EXPIRED, message = null)
        }
    }

    suspend fun poll() {
        val current = session ?: return
        val now = clock()
        if (current.isExpiredAt(now)) {
            _state.value = _state.value.copy(status = PairingStatus.EXPIRED)
            return
        }
        when (val reply = backend.pollStatus(current.token)) {
            is BackendReply.Ok -> _state.value = _state.value.copy(
                status = reply.status,
                backendConfigured = true,
            )

            BackendReply.NotConfigured -> _state.value = _state.value.copy(backendConfigured = false)

            is BackendReply.Failure -> _state.value = _state.value.copy(
                status = PairingStatus.ERROR,
                message = reply.reason,
            )
        }
    }

    fun reset() {
        session = null
        _state.value = PairingState()
    }

    private companion object {
        fun randomToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString(separator = "") { "%02x".format(it) }
        }
    }
}
