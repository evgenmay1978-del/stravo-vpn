package com.stravo.vpn.data.subscription

import com.stravo.vpn.domain.model.Subscription
import com.stravo.vpn.domain.subscription.Base64Codec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Common INCY/HAPP display headers; never execute provider routing or auto-fetch URLs. */
internal object SubscriptionMetadata {
    fun read(body: String, header: (String) -> String? = { null }): Subscription {
        val plain = Base64Codec.decodeOrNull(body.trim()) ?: body
        val inline = plain.lineSequence().map { it.trim().removePrefix("\uFEFF") }
            .filter { it.startsWith("#") && ':' in it }
            .associate { it.substringBefore(':').removePrefix("#").trim().lowercase(Locale.ROOT) to
                it.substringAfter(':').trim() }
        fun value(name: String) = header(name) ?: inline[name]
        fun text(name: String, limit: Int): String? {
            val raw = value(name)?.trim() ?: return null
            val decoded = if (raw.startsWith("base64:", ignoreCase = true))
                Base64Codec.decodeOrNull(raw.substringAfter(':')) ?: return null else raw
            return decoded.filter { !it.isISOControl() || it == '\n' }
                .trim().take(limit).takeIf { it.isNotEmpty() }
        }
        val usage = value("subscription-userinfo").orEmpty().split(';')
            .filter { '=' in it }.associate {
                it.substringBefore('=').trim().lowercase(Locale.ROOT) to
                    it.substringAfter('=').trim().toLongOrNull()?.takeIf { bytes -> bytes >= 0 }
            }
        val upload = usage["upload"]
        val download = usage["download"]
        val used = if (upload == null && download == null) null else
            (upload ?: 0L).let { up -> up + (download ?: 0L).coerceAtMost(Long.MAX_VALUE - up) }
        val expiry = usage["expire"]?.let { if (it > 32_000_000_000L) it / 1000 else it }
            ?.takeIf { it in 1..253_402_300_799L }
            ?.let { SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(it * 1000)) }
        return Subscription(
            planName = (text("profile-title", 128) ?: text("subscription-name", 128)
                ?: "STRAVO VPN").replace('\n', ' '),
            activeUntil = expiry,
            isActive = true,
            description = text("profile-description", 512),
            announcement = text("announce", 1024),
            usedBytes = used,
            totalBytes = usage["total"]?.takeIf { it > 0 },
        )
    }
}
