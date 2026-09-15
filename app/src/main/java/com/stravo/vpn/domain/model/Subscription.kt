package com.stravo.vpn.domain.model

@JvmInline
value class SubscriptionId(val value: String)

data class Subscription(
    val id: SubscriptionId,
    val displayName: String,
    val profileIds: List<ProfileId>,
    val refreshIntervalMinutes: Int?,
    val expiresAtEpochSeconds: Long?,
)
