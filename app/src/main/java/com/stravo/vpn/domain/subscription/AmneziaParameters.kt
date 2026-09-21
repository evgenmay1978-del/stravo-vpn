package com.stravo.vpn.domain.subscription

import org.json.JSONObject

/** Mapping for the shipped with_awg core. Values contain secrets: never log them.
 * Schema: sing-box-lx option/wireguard_awg.go, device_awg.go and SPEC 080.
 * Option blob: bd18c5e31b35513e768c43a4799ce877e6c00f68 (read through GitHub contents API).
 * Keep endpoint fields flat; id/ip/ib masquerade sugar is deliberately unsupported.
 */
internal object AmneziaParameters {
    private const val UINT32_MAX = 4294967295L
    private const val MAX_PACKET_SIZE = 65535L
    private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val schemes = setOf("amneziawg", "awg")
    private val integers = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")
    private val ranges = setOf("h1", "h2", "h3", "h4", "content_padding_addition",
        "rekey_after_time", "rekey_timeout", "reject_after_time", "keepalive_timeout", "max_handshake_attempts")
    private val sequences = setOf("i1", "i2", "i3", "i4", "i5")
    private val switches = setOf("random_trailers", "disable_cookies")
    private val fieldNames = (integers + ranges + sequences + switches + "header_protection_key")
        .associateBy(::normalized)
    val keys: Set<String> = fieldNames.keys

    fun normalized(value: String): String = value.lowercase().replace("_", "").replace("-", "")

    /** Input keys are normalized; JSON names and types match the native endpoint model. */
    fun options(values: Map<String, String>): JSONObject {
        val result = JSONObject()
        for ((key, raw) in values) {
            val field = fieldNames[key] ?: continue // Caller rejects fields outside its complete allowlist.
            val value: Any = when (field) {
                in integers -> unsigned(raw, if (field.startsWith('s')) MAX_PACKET_SIZE else UINT32_MAX)
                in ranges -> range(raw)
                in sequences -> cps(raw)
                in switches -> when (raw.lowercase()) {
                    "1", "true", "on", "yes", "enabled" -> true
                    "0", "false", "off", "no", "disabled" -> false
                    else -> throw IllegalArgumentException()
                }
                else -> headerKey(raw)
            }
            result.put(field, value)
        }
        require(result.optLong("jmin") <= result.optLong("jmax"))
        if (result.has("header_protection_key")) {
            require((1..4).all { result.optLong("s$it") >= 12 })
        }
        return result
    }

    fun isEndpoint(endpoint: JSONObject): Boolean {
        if (fieldNames.values.any { endpoint.has(it) }) return true
        val peers = endpoint.optJSONArray("peers") ?: return false
        return (0 until peers.length()).any {
            peers.getJSONObject(it).optString("persistent_keepalive_interval").contains('-')
        }
    }

    /** Singles stay JSON numbers, genuine ranges stay strings. Zero retains native unset semantics. */
    fun range(raw: String, maximum: Long = UINT32_MAX): Any {
        val parts = raw.trim().split('-')
        require(parts.size in 1..2)
        val low = unsigned(parts.first().trim(), maximum)
        val high = unsigned(parts.last().trim(), maximum)
        require(low <= high)
        return if (low == high) low else "$low-$high"
    }

    private fun unsigned(raw: String, maximum: Long): Long {
        require(raw.isNotEmpty() && raw.all { it in '0'..'9' })
        return (raw.toLongOrNull() ?: throw IllegalArgumentException()).also { require(it in 0..maximum) }
    }

    /** Accept .conf base64 or 64 hex digits; the sing-box JSON API requires standard base64. */
    private fun headerKey(raw: String): String {
        val encoded = if (Regex("[0-9a-fA-F]{64}").matches(raw)) {
            val bytes = raw.chunked(2).map { it.toInt(16) }
            buildString {
                for (offset in bytes.indices step 3) {
                    val a = bytes[offset]
                    val b = bytes.getOrElse(offset + 1) { 0 }
                    val c = bytes.getOrElse(offset + 2) { 0 }
                    append(BASE64[a shr 2])
                    append(BASE64[((a and 3) shl 4) or (b shr 4)])
                    append(BASE64[((b and 15) shl 2) or (c shr 6)])
                    append(if (offset + 2 < bytes.size) BASE64[c and 63] else '=')
                }
            }
        } else raw.replace('-', '+').replace('_', '/').removeSuffix("=") + "="
        require(Regex("[A-Za-z0-9+/]{43}=").matches(encoded))
        require(encoded[42] in "AEIMQUYcgkosw048")
        require(encoded != "A".repeat(43) + "=")
        return encoded
    }

    /** Validate the vendored obf.go grammar without executing it or changing its case.
     * Reject text/extra arguments the native parser would silently ignore. I-packets have no data.
     */
    private fun cps(raw: String): String {
        require(raw.length <= 16384 && raw.none { it.isISOControl() && it != '\t' })
        var offset = 0
        var packetSize = 0L
        while (offset < raw.length) {
            if (raw[offset].isWhitespace()) { offset++; continue }
            require(raw[offset] == '<')
            val end = raw.indexOf('>', offset + 1)
            require(end >= 0)
            val parts = raw.substring(offset + 1, end).trim().split(Regex("\\s+"))
            val token = parts.first()
            val size = when (token) {
                "b" -> {
                    require(parts.size == 2)
                    val hex = parts[1].removePrefix("0x")
                    require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it in "0123456789abcdefABCDEF" })
                    hex.length.toLong() / 2
                }
                "r", "rc", "rd", "dz" -> {
                    require(parts.size == 2)
                    unsigned(parts[1], MAX_PACKET_SIZE)
                }
                "t", "d", "ds" -> {
                    require(parts.size == 1)
                    if (token == "t") 4L else 0L
                }
                else -> throw WireGuardProfile.Unsupported("AmneziaWG: неподдерживаемый токен CPS")
            }
            packetSize += size
            require(packetSize <= MAX_PACKET_SIZE)
            offset = end + 1
        }
        return raw
    }

    /** Only a base64/base64url UTF-8 .conf, never a nested URI or compressed vpn:// container. */
    fun decodeConf(payload: String): String {
        require(payload.length in 1..WireGuardProfile.MAX_LINK_LENGTH)
        val text = payload.replace('-', '+').replace('_', '/')
        val bare = text.trimEnd('=')
        val padding = text.length - bare.length
        require(bare.isNotEmpty() && bare.all { it in BASE64 } && bare.length % 4 != 1)
        require(padding in 0..2 && (padding == 0 || (text.length % 4 == 0 && padding == (4 - bare.length % 4) % 4)))
        val trailingBits = when (bare.length % 4) { 2 -> 15; 3 -> 3; else -> 0 }
        require((BASE64.indexOf(bare.last()) and trailingBits) == 0)
        val decoded = Base64Codec.decodeOrNull(text) ?: throw IllegalArgumentException()
        require(decoded.none { it == '\uFFFD' || (it.isISOControl() && it !in "\r\n\t") })
        require(WireGuardProfile.looksLikeConf(decoded))
        return decoded
    }
}
