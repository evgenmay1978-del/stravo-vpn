package com.stravo.vpn.data.subscription

import com.stravo.vpn.domain.model.Subscription
import com.stravo.vpn.domain.model.SubscriptionSource
import com.stravo.vpn.domain.subscription.Base64Codec
import java.net.URI
import java.net.URLDecoder
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
        fun value(name: String) = header(name)?.takeIf { it.isNotBlank() } ?: inline[name]
        fun text(name: String, limit: Int): String? {
            val raw = value(name)?.trim() ?: return null
            val decoded = if (raw.startsWith("base64:", ignoreCase = true))
                Base64Codec.decodeOrNull(raw.substringAfter(':')) ?: return null else raw
            return safeText(decoded, limit)
        }
        fun link(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
            value(name)?.let(::safeHttpUrl)
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
                ?: text("title", 128) ?: text("name", 128)
                ?: dispositionTitle(value("content-disposition"))
                ?: "STRAVO VPN").replace('\n', ' '),
            activeUntil = expiry,
            isActive = true,
            description = text("profile-description", 512),
            announcement = text("announce", 1024),
            usedBytes = used,
            totalBytes = usage["total"]?.takeIf { it > 0 },
            updateIntervalHours = value("profile-update-interval")?.trim()?.toIntOrNull()
                ?.takeIf { it in 0..SubscriptionSource.MAX_UPDATE_INTERVAL_HOURS },
            supportUrl = link("support-url"),
            homepageUrl = link("profile-web-page-url", "homepage"),
            announcementUrl = link("announce-url"),
        )
    }

    /** Display/open only after a user action. No custom schemes, credentials or control characters. */
    fun safeHttpUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 } ?: return null
        if (value.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) return null
        return runCatching {
            val uri = URI(value)
            value.takeIf {
                uri.scheme?.lowercase(Locale.ROOT) in setOf("https", "http") &&
                    !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                    (uri.port == -1 || uri.port in 1..65535)
            }
        }.getOrNull()
    }

    private fun safeText(raw: String, limit: Int): String? = raw.filter {
        (!it.isISOControl() || it == '\n') && Character.getType(it) != Character.FORMAT.toInt()
    }.trim().take(limit).takeIf { it.isNotEmpty() }

    /** RFC 6266: prefer filename*, decode its charset, ignore advisory directory components. */
    private fun dispositionTitle(raw: String?): String? {
        if (raw == null) return null
        val fields = Regex("""(?:^|;)\s*(filename\*?)\s*=\s*(?:"((?:\\.|[^"\\])*)"|([^;]*))""",
            RegexOption.IGNORE_CASE).findAll(raw).associate { match ->
            match.groupValues[1].lowercase(Locale.ROOT) to
                (match.groups[2]?.value?.replace(Regex("""\\(.)"""), "$1")
                    ?: match.groupValues[3].trim())
        }
        val extended = fields["filename*"]?.split('\'', limit = 3)?.takeIf { it.size == 3 }
            ?.let { parts ->
                val charset = parts[0].uppercase(Locale.ROOT)
                if (charset !in setOf("UTF-8", "ISO-8859-1")) null else runCatching {
                    URLDecoder.decode(parts[2].replace("+", "%2B"), charset)
                }.getOrNull()
            }
        val filename = (extended ?: fields["filename"])?.substringAfterLast('/')
            ?.substringAfterLast('\\') ?: return null
        return safeText(filename, 128)?.takeUnless { it in setOf(".", "..", "~") }
    }
}
