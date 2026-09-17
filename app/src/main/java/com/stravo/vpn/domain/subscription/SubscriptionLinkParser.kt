package com.stravo.vpn.domain.subscription

import com.stravo.vpn.domain.model.ProtocolCatalog
import com.stravo.vpn.domain.model.VpnProtocol
import com.stravo.vpn.domain.model.VpnSecurity
import com.stravo.vpn.domain.model.VpnTransport
import java.security.MessageDigest

/**
 * Разбор ссылок подписки: vless://, anytls://, hysteria2://, trojan://, ss:// и https-ссылки
 * на подписку. Разбор полностью офлайновый и никуда не ходит.
 *
 * Секреты (UUID, пароли, ключи Reality) остаются внутри [ParsedLink.Node.secretConfig]
 * и наружу не отдаются.
 */
object SubscriptionLinkParser {

    private const val MAX_LINK_LENGTH = 8192

    fun parse(raw: String): ParsedLink {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_LINK_LENGTH) return ParsedLink.Unknown
        if (!value.contains("://")) return ParsedLink.Unknown

        val scheme = value.substringBefore("://").lowercase()
        if (scheme == "http" || scheme == "https") return ParsedLink.SubscriptionUrl(value)

        val protocol = ProtocolCatalog.byScheme(scheme) ?: return ParsedLink.Unknown
        return parseNode(value, protocol) ?: ParsedLink.Unknown
    }

    /** Разбирает строку целиком; если это не ссылка — возвращает null. */
    fun parseNode(raw: String, protocol: VpnProtocol): ParsedLink.Node? {
        val value = raw.trim()
        val afterScheme = value.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null

        val fragment = afterScheme.substringAfter('#', "")
        val withoutFragment = afterScheme.substringBefore('#')
        val query = withoutFragment.substringAfter('?', "")
        val authority = withoutFragment.substringBefore('?')

        val params = parseQuery(query)
        val userInfo = if (authority.contains('@')) authority.substringBeforeLast('@') else ""
        val hostPort = if (authority.contains('@')) authority.substringAfterLast('@') else authority

        val host = hostOf(hostPort) ?: return null
        val port = portOf(hostPort, defaultPort(protocol))
        if (port !in 1..65535) return null

        val transport = resolveTransport(protocol, params)
        val security = resolveSecurity(protocol, params)
        val name = fragment.takeIf { it.isNotBlank() }?.let { decodeComponent(it) }
            ?: defaultName(protocol, transport, security)

        val node = SubscriptionNode(
            id = fingerprint(value),
            name = name,
            protocolId = protocol.id,
            protocolLabel = protocol.displayName,
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

    private fun defaultPort(protocol: VpnProtocol): Int = when (protocol.id) {
        "hysteria2" -> 443
        "anytls" -> 443
        "trojan" -> 443
        else -> 443
    }

    private fun hostOf(hostPort: String): String? {
        if (hostPort.isEmpty()) return null
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end <= 1) return null
            return hostPort.substring(1, end)
        }
        val host = if (hostPort.contains(':')) hostPort.substringBeforeLast(':') else hostPort
        return host.ifEmpty { null }
    }

    private fun portOf(hostPort: String, fallback: Int): Int {
        val raw = if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return fallback
            hostPort.substring(end + 1).removePrefix(":")
        } else if (hostPort.contains(':')) {
            hostPort.substringAfterLast(':')
        } else {
            ""
        }
        return raw.toIntOrNull() ?: fallback
    }

    private fun resolveTransport(protocol: VpnProtocol, params: Map<String, String>): VpnTransport {
        val type = params["type"] ?: params["transport"] ?: params["net"]
        if (type == null) return protocol.defaultTransport
        val transport = VpnTransport.byId(type)
        if (transport == VpnTransport.UNKNOWN) return protocol.defaultTransport
        // Ядро всё равно попробует транспорт, даже если каталог его не заявлял:
        // показываем честно то, что написано в конфиге.
        return transport
    }

    private fun resolveSecurity(protocol: VpnProtocol, params: Map<String, String>): VpnSecurity {
        val security = params["security"] ?: params["tls"]
        if (security == null) return protocol.defaultSecurity
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
                char == '%' && index + 2 < value.length + 1 && index + 2 <= value.length - 1 -> {
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
}

