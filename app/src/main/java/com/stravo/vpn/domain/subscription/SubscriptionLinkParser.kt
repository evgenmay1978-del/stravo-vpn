package com.stravo.vpn.domain.subscription

import com.stravo.vpn.domain.model.ProtocolCatalog
import com.stravo.vpn.domain.model.VpnProtocol
import com.stravo.vpn.domain.model.VpnSecurity
import com.stravo.vpn.domain.model.VpnTransport
import java.security.MessageDigest

/**
 * Разбор ссылок подписки. Полностью офлайновый, никуда не ходит.
 *
 * Понимает три вида строк:
 *  1. ссылка подписки — http(s), в том числе без схемы (голый домен sub.example.com/x);
 *  2. одиночный ключ — VLESS, VMess, AnyTLS, Hysteria2, Trojan, SS, SOCKS5, TUIC,
 *     либо HTTP(S) proxy с user:password@host:port;
 *  3. ссылка-обёртка клиента — happ://add/…, incy://…, sub://…, v2rayng://install-sub?url=…,
 *     clash://install-config?url=…, sing-box://import-remote-profile?url=… и другие:
 *     из неё достаётся вложенный http(s)-адрес или ключ, в том числе из base64.
 *
 * Секреты (UUID, пароли, ключи Reality) остаются внутри [ParsedLink.Node.secretConfig]
 * и наружу не отдаются.
 */
object SubscriptionLinkParser {

    private const val MAX_LINK_LENGTH = WireGuardProfile.MAX_LINK_LENGTH

    /** Схемы клиентов-обёрток: сами по себе они контейнер, а не адрес подписки. */
    private val wrapperSchemes: Set<String> = setOf(
        "stravo", "happ", "incy", "sub", "v2rayng", "v2raytun", "clash", "clashmeta",
        "sing-box", "singbox", "streisand", "shadowrocket", "karing",
        "hiddify", "nekobox", "nekoray", "foxray", "stash", "quantumult",
        "loon", "surge", "sn",
    )

    private val schemePattern = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://")
    private val hostPattern = Regex("^[A-Za-z0-9]([A-Za-z0-9\\-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9\\-]*[A-Za-z0-9])?)+$")

    fun parse(raw: String): ParsedLink {
        val value = raw.trim()
        if (value.isEmpty()) return ParsedLink.Unknown(Unrecognized.EMPTY)
        if (value.length > MAX_LINK_LENGTH) return ParsedLink.Unknown(Unrecognized.TOO_LONG)
        if (value.any { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) {
            return ParsedLink.Unknown(Unrecognized.NOT_A_SINGLE_LINK)
        }

        val scheme = schemeOf(value)
        if (scheme == null) {
            val asDomain = bareDomain(value)
            return if (asDomain != null) {
                ParsedLink.SubscriptionUrl(asDomain)
            } else {
                ParsedLink.Unknown(Unrecognized.NO_SCHEME)
            }
        }

        if ((scheme == "http" || scheme == "https") && !isCredentialProxy(value)) {
            return ParsedLink.SubscriptionUrl(value)
        }

        // App import wrappers take precedence over the legacy stravo/WebRTC alias.
        if (scheme in wrapperSchemes) return unwrap(value, scheme)

        val protocol = ProtocolCatalog.byScheme(scheme)
        if (protocol != null) {
            return parseNode(value, protocol) ?: ParsedLink.Unknown(Unrecognized.BROKEN_KEY, scheme)
        }

        return ParsedLink.Unknown(Unrecognized.UNKNOWN_SCHEME, scheme)
    }

    /** Разбирает строку целиком; если это не ссылка — возвращает null. */
    fun parseNode(raw: String, protocol: VpnProtocol): ParsedLink.Node? {
        val input = raw.trim()
        if (input.length > MAX_LINK_LENGTH || schemeOf(input) !in protocol.schemes) return null
        val wireguard = WireGuardProfile.isLink(input)
        val value = if (wireguard) {
            runCatching { WireGuardProfile.normalizeLink(input) }.getOrNull() ?: return null
        } else input
        val parsed = ProxyShareLink.parse(value) ?: return null
        val endpoint = if (wireguard) {
            runCatching { WireGuardProfile.endpoint(value, "proxy") }.getOrNull() ?: return null
        } else null
        val actualProtocol = if (endpoint != null &&
            (parsed.scheme in AmneziaParameters.schemes || AmneziaParameters.isEndpoint(endpoint))) {
            ProtocolCatalog.AMNEZIAWG
        } else protocol
        val credential = runCatching { ProxyShareLink.decode(parsed.userInfo) }.getOrNull() ?: return null
        if (protocol.id in setOf("vless", "vmess") && !ProxyShareLink.isUuid(credential)) return null
        if (protocol.id == "tuic" && (!credential.contains(':') || !ProxyShareLink.isUuid(credential.substringBefore(':')))) return null
        if (protocol.id in setOf("trojan", "hysteria2", "anytls", "shadowsocks", "http") && credential.isEmpty()) return null
        val params = parsed.query
        val transport = resolveTransport(actualProtocol, params)
        val security = if (parsed.scheme == "https") VpnSecurity.TLS else resolveSecurity(actualProtocol, params)
        val name = parsed.name ?: defaultName(actualProtocol, transport, security)

        val node = SubscriptionNode(
            id = fingerprint(value),
            name = name,
            protocolId = actualProtocol.id,
            protocolLabel = actualProtocol.displayName,
            transport = transport,
            security = security,
        )
        return ParsedLink.Node(node = node, secretConfig = value)
    }

    /** Идентификатор узла: короткий хеш конфига. По нему нельзя восстановить секрет. */
    fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(16)
        for (index in 0 until 8) {
            val byte = digest[index].toInt() and 0xFF
            builder.append(HEX[byte shr 4]).append(HEX[byte and 0x0F])
        }
        return builder.toString()
    }

    // --- Ссылки-обёртки ---------------------------------------------------

    private fun unwrap(value: String, scheme: String): ParsedLink {
        val body = value.substringAfter("://", "").trim()
        if (body.isEmpty()) return ParsedLink.Unknown(Unrecognized.BROKEN_IMPORT_LINK, scheme)

        val query = body.substringAfter('?', "")
        if (query.isNotEmpty()) {
            val url = parseQuery(query)["url"]
            if (!url.isNullOrBlank()) return embedded(url, scheme)
        }

        findHttpUrl(body)?.let { return embedded(it, scheme) }

        // Адрес может быть процентно закодирован: happ://add/https%3A%2F%2F…
        val decodedBody = decodeComponent(body)
        if (decodedBody != body) {
            findHttpUrl(decodedBody)?.let { return embedded(it, scheme) }
        }

        // Хвост строки может быть base64 от адреса: happ://add/aHR0cHM6Ly8…
        val candidates = listOf(body, body.substringAfterLast('/'))
        for (candidate in candidates) {
            val decoded = Base64Codec.decodeOrNull(candidate)?.trim() ?: continue
            findHttpUrl(decoded)?.let { return embedded(it, scheme) }
        }

        return ParsedLink.Unknown(Unrecognized.BROKEN_IMPORT_LINK, scheme)
    }

    /** Вложенная ссылка внутри обёртки: http(s)-адрес или ключ протокола. */
    private fun embedded(inner: String, wrapper: String): ParsedLink {
        val value = inner.trim()
        val scheme = schemeOf(value) ?: return ParsedLink.Unknown(Unrecognized.BROKEN_IMPORT_LINK, wrapper)
        if ((scheme == "http" || scheme == "https") && !isCredentialProxy(value)) return ParsedLink.SubscriptionUrl(value)
        val protocol = ProtocolCatalog.byScheme(scheme)
            ?: return ParsedLink.Unknown(Unrecognized.BROKEN_IMPORT_LINK, wrapper)
        return parseNode(value, protocol) ?: ParsedLink.Unknown(Unrecognized.BROKEN_KEY, scheme)
    }

    private fun findHttpUrl(text: String): String? {
        val lower = text.lowercase()
        val https = lower.indexOf("https://")
        val http = lower.indexOf("http://")
        val start = when {
            https < 0 -> http
            http < 0 -> https
            else -> minOf(https, http)
        }
        if (start < 0) return null
        val tail = text.substring(start)
        val cut = tail.indexOfAny(charArrayOf('&', '#', ' ', '\t', '\n', '\r', '"', '\''))
        val candidate = (if (cut > 0) tail.substring(0, cut) else tail).trimEnd()
        return candidate.takeIf { it.length > HTTP_PREFIX_MIN }
    }

    private fun schemeOf(value: String): String? =
        schemePattern.find(value)?.groupValues?.get(1)?.lowercase()

    /** Голый домен без схемы: sub.example.com/path → https://sub.example.com/path. */
    private fun bareDomain(value: String): String? {
        val head = value.substringBefore('/').substringBefore('?').substringBefore('#')
        if (head.isEmpty()) return null
        val hostPort = if (head.contains('@')) head.substringAfterLast('@') else head
        val host = hostPort.substringBefore(':')
        if (!hostPattern.matches(host)) return null
        return "https://$value"
    }

    // --- Протокол узла ----------------------------------------------------

    private fun defaultName(
        protocol: VpnProtocol,
        transport: VpnTransport,
        security: VpnSecurity,
    ): String = buildString {
        append(protocol.displayName)
        append(" · ")
        append(transport.displayName)
        if (security != VpnSecurity.NONE) {
            append(" · ")
            append(security.displayName)
        }
    }

    /** Bare HTTP(S) URLs remain subscriptions; credentials + authority-only denotes a proxy. */
    private fun isCredentialProxy(value: String): Boolean {
        val authority = value.substringAfter("://").substringBefore('?').substringBefore('#').removeSuffix("/")
        return '@' in authority && '/' !in authority.substringAfterLast('@')
    }

    private fun resolveTransport(protocol: VpnProtocol, params: Map<String, String>): VpnTransport {
        val type = params["type"] ?: params["transport"] ?: params["net"]
        if (type == null) return protocol.defaultTransport
        val transport = VpnTransport.byId(type)
        return if (protocol.supports(transport)) transport else VpnTransport.UNKNOWN
    }

    private fun resolveSecurity(protocol: VpnProtocol, params: Map<String, String>): VpnSecurity {
        val security = params["security"] ?: params["tls"]
        if (security == null) return if (!params["pbk"].isNullOrBlank()) VpnSecurity.REALITY else protocol.defaultSecurity
        return when (security.trim().lowercase()) {
            "none", "0", "false" -> VpnSecurity.NONE
            "tls", "1", "true" -> VpnSecurity.TLS
            "reality" -> VpnSecurity.REALITY
            else -> protocol.defaultSecurity
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = decodeComponent(pair.substringBefore('=')).lowercase()
            if (key.isEmpty()) continue
            val value = if (pair.contains('=')) decodeComponent(pair.substringAfter('=')) else ""
            result[key] = value
        }
        return result
    }

    /**
     * Процентное декодирование. Сделано вручную, чтобы не зависеть от java.net.URLDecoder
     * и одинаково работать на всех поддерживаемых версиях Android.
     */
    fun decodeComponent(value: String): String {
        if (value.isEmpty()) return value
        if (!value.contains('%') && !value.contains('+')) return value
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '%' && index + 2 <= value.length - 1 -> {
                    val code = value.substring(index + 1, index + 3).toIntOrNull(16)
                    if (code == null) {
                        bytes.write(char.code)
                        index++
                    } else {
                        bytes.write(code)
                        index += 3
                    }
                }

                char == '+' -> {
                    bytes.write(' '.code)
                    index++
                }

                else -> {
                    bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                    index++
                }
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private const val HEX = "0123456789abcdef"
    private const val HTTP_PREFIX_MIN = 8
}
