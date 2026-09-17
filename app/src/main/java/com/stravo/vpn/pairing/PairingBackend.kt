package com.stravo.vpn.pairing

sealed interface BackendReply {
    data class Ok(val status: PairingStatus) : BackendReply

    /** На стороне сервиса ещё нет pairing-API. Это не ошибка клиента. */
    data object NotConfigured : BackendReply

    data class Failure(val reason: String) : BackendReply
}

/**
 * Контракт серверной части переноса подписки phone → TV.
 *
 * Эндпоинты, которые должен предоставить сервис:
 *   POST /pairing/session              -> { token, expires_at }
 *   GET  /pairing/session/{token}      -> { status: waiting|claimed|importing|success|expired }
 *   POST /pairing/session/{token}/claim (из Telegram-бота, после подтверждения пользователя)
 *
 * Токен одноразовый, живёт 2–5 минут, после claim повторно не принимается.
 */
interface PairingBackend {
    suspend fun createSession(token: String): BackendReply
    suspend fun pollStatus(token: String): BackendReply
}

/**
 * Заглушка: pairing-API пока не опубликован.
 * Клиент честно сообщает об этом вместо имитации успешного переноса.
 */
class StubPairingBackend : PairingBackend {
    override suspend fun createSession(token: String): BackendReply = BackendReply.NotConfigured

    override suspend fun pollStatus(token: String): BackendReply = BackendReply.NotConfigured
}
