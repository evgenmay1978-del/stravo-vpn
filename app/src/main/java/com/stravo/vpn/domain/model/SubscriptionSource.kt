package com.stravo.vpn.domain.model

import com.stravo.vpn.domain.subscription.SubscriptionService

/** Public source identity and metadata. The subscription URL belongs only in SecretStore. */
data class SubscriptionSource(
    val id: String,
    val service: SubscriptionService,
    val subscription: Subscription,
    val enabled: Boolean = true,
    val name: String = subscription.planName,
    /** Last successful import/refresh, in epoch milliseconds; zero means pending. */
    val updatedAt: Long = 0L,
    /** Zero disables scheduled refresh; explicit refresh remains available. */
    val updateIntervalHours: Int = DEFAULT_UPDATE_INTERVAL_HOURS,
) {
    fun isRefreshDue(now: Long = System.currentTimeMillis()): Boolean = enabled &&
        updateIntervalHours > 0 && (updatedAt <= 0L || now < updatedAt ||
        now - updatedAt >= updateIntervalHours.toLong() * 60 * 60 * 1000)

    companion object {
        const val DEFAULT_UPDATE_INTERVAL_HOURS = 6
        const val MAX_UPDATE_INTERVAL_HOURS = 24 * 365
    }
}
