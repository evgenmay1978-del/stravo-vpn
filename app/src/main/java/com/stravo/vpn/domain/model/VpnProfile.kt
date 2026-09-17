package com.stravo.vpn.domain.model

/**
 * Профиль подключения без секретов: идентификатор, читаемое имя и подсказка по протоколу.
 * Полный конфиг (URL подписки, UUID, ключи) в репозиторий и в логи не попадает.
 */
data class VpnProfile(
    val id: String,
    val title: String,
    val protocolHint: String? = null,
)
