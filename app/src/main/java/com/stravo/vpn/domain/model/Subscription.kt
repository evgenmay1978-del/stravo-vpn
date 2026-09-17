package com.stravo.vpn.domain.model

/**
 * Подписка. В модели СОЗНАТЕЛЬНО нет URL, UUID и ключей — только безопасные метаданные,
 * которые можно показывать и логировать.
 */
data class Subscription(
    val planName: String,
    val activeUntil: String?,
    val isActive: Boolean,
) {
    companion object {
        val None: Subscription = Subscription(planName = "Нет активной подписки", activeUntil = null, isActive = false)
    }
}
