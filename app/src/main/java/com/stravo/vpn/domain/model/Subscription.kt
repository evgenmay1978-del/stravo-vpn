package com.stravo.vpn.domain.model

/**
 * Метаданные подписки для UI. Произвольный текст провайдера не логировать.
 * Ссылки источников и конфигурации узлов остаются в SecretStore.
 */
data class Subscription(
    val planName: String,
    val activeUntil: String?,
    val isActive: Boolean,
    val description: String? = null,
    val announcement: String? = null,
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    val updateIntervalHours: Int? = null,
    val supportUrl: String? = null,
    val homepageUrl: String? = null,
    val announcementUrl: String? = null,
) {
    companion object {
        val None: Subscription = Subscription(planName = "Нет активной подписки", activeUntil = null, isActive = false)
    }
}
