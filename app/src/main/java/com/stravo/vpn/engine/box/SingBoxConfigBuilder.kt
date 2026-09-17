package com.stravo.vpn.engine.box

import com.stravo.vpn.domain.subscription.SubscriptionLinkParser
import org.json.JSONArray
import org.json.JSONObject

/** Результат сборки конфига для ядра. Секреты остаются внутри [Ready.json]. */
sealed interface CoreConfig {

    /** Конфиг готов: [json] уходит только в ядро. */
    data class Ready(val json: String) : CoreConfig

    /** Транспорт разобран в подписке, но это ядро его не умеет (например, XHTTP). */
    data class Unsupported(val transport: String) : CoreConfig

    /** Ссылку не удалось разобрать: отдавать ядру нечего. */
    data object Broken : CoreConfig
}

/**
 * Собирает JSON sing-box из ссылки узла подписки.
 *
 * Поддерживаются VLESS (TCP, WebSocket, HTTP Upgrade, gRPC, QUIC), AnyTLS, Hysteria2,
 * Trojan и Shadowsocks — то, что закрывает libbox. Транспорт XHTTP ядру не отдаётся:
 * вместо тихого отказа возвращается [CoreConfig.Unsupported].
 *
 * Конфиг никогда не логируется и не попадает в UI-состояние.
 */
object SingBoxConfigBuilder {

    private const val TAG_PROXY = "proxy"
    private const val TAG_DIRECT = "direct"
    private const val TUN_IPV4 = "172.19.0.1/30"
    private const val TUN_IPV6 = "fdfe:dcba:9876::1/126"

    /** Транспорты, которых нет в этой сборке ядра (docs/IMPLEMENTATION.md, раздел 1c). */
    private val unsupportedTransports = setOf("xhttp", "splithttp")

    fun build(link: String): CoreConfig {
        val value = link.trim()
        val scheme = value.substringBefore("://", "").lowercase()
        if (scheme.isEmpty() || !value.contains("://")) return CoreConfig.Broken

        val parsed = parse(value) ?: return CoreConfig.Broken
        val transport = parsed.query["type"] ?: parsed.query["net"] ?: ""
        if (transport.lowercase() in unsupportedTransports) {
            return CoreConfig.Unsupported(transport.uppercase())
        }

        val outbound = when (scheme) {
            "vless" -> vless(parsed) ?: return CoreConfig.Broken
            "trojan" -> trojan(parsed) ?: return CoreConfig.Broken
            "hysteria2", "hy2" -> hysteria2(parsed) ?: return CoreConfig.Broken
            "anytls" -> anytls(parsed) ?: return CoreConfig.Broken
            "ss" -> shadowsocks(parsed) ?: return CoreConfig.Broken
            else -> return CoreConfig.Broken
        }

        return CoreConfig.Ready(assemble(outbound).toString())
    }

    // --- Протоколы --------------------------------------------------------

    private fun vless(link: Link): JSONObject? {
        val uuid = decode(link.userInfo.substringBefore(':'))
        if (uuid.isEmpty() || link.host.isEmpty()) return null
        val outbound = JSONObject()
            .put("type", "vless")
            .put("tag", TAG_PROXY)
            .put("server", link.host)
            .put("server_port", link.port)
            .put("uuid", uuid)
        link.query["flow"]?.takeIf { it.isNotBlank() }?.let { outbound.put("flow", it) }
        applyTls(link, outbound, default = false)
        applyTransport(link, outbound)
        return outbound
    }

    private fun trojan(link: Link): JSONObject? {
        val password = decode(link.userInfo.substringBefore(':'))
        if (password.isEmpty() || link.host.isEmpty()) return null
        val outbound = JSONObject()
            .put("type", "trojan")
            .put("tag", TAG_PROXY)
            .put("server", link.host)
            .put("server_port", link.port)
            .put("password", password)
        applyTls(link, outbound, default = true)
        applyTransport(link, outbound)
        return outbound
    }

    private fun hysteria2(link: Link): JSONObject? {
        val password = decode(link.userInfo.substringBefore(':'))
        if (password.isEmpty() || link.host.isEmpty()) return null
        val outbound = JSONObject()
            .put("type", "hysteria2")
            .put("tag", TAG_PROXY)
            .put("server", link.host)
            .put("server_port", link.port)
            .put("password", password)
        val obfs = link.query["obfs"]
        if (!obfs.isNullOrBlank() && obfs != "none") {
            outbound.put(
                "obfs",
                JSONObject().put("type", obfs).put("password", link.query["obfs-password"] ?: ""),
            )
        }
        applyTls(link, outbound, default = true)
        return outbound
    }

    private fun anytls(link: Link): JSONObject? {
        val password = decode(link.userInfo.substringBefore(':'))
        if (password.isEmpty() || link.host.isEmpty()) return null
        val outbound = JSONObject()
            .put("type", "anytls")
            .put("tag", TAG_PROXY)
            .put("server", link.host)
            .put("server_port", link.port)
            .put("password", password)
        applyTls(link, outbound, default = true)
        return outbound
    }

    private fun shadowsocks(link: Link): JSONObject? {
        if (link.host.isEmpty()) return null
        val credentials = if (link.userInfo.contains(':')) link.userInfo else decode(link.userInfo)
        val method = credentials.substringBefore(':').lowercase()
        val password = credentials.substringAfter(':', "")
        if (method.isEmpty() || password.isEmpty()) return null
        return JSONObject()
            .put("type", "shadowsocks")
            .put("tag", TAG_PROXY)
            .put("server", link.host)
            .put("server_port", link.port)
            .put("method", method)
            .put("password", password)
    }

    // --- Транспорт и защита ----------------------------------------------

    private fun applyTransport(link: Link, outbound: JSONObject) {
        val type = (link.query["type"] ?: link.query["net"] ?: "tcp").lowercase()
        when (type) {
            "ws", "websocket" -> {
                val transport = JSONObject().put("type", "ws")
                link.query["path"]?.takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
                link.query["host"]?.takeIf { it.isNotBlank() }?.let {
                    transport.put("headers", JSONObject().put("Host", it))
                }
                outbound.put("transport", transport)
            }

            "httpupgrade" -> {
                val transport = JSONObject().put("type", "httpupgrade")
                link.query["path"]?.takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
                link.query["host"]?.takeIf { it.isNotBlank() }?.let { transport.put("host", it) }
                outbound.put("transport", transport)
            }

            "grpc" -> {
                val transport = JSONObject().put("type", "grpc")
                val service = link.query["servicename"]
                service?.takeIf { it.isNotBlank() }?.let { transport.put("service_name", it) }
                outbound.put("transport", transport)
            }

            "quic" -> outbound.put("transport", JSONObject().put("type", "quic"))
            else -> Unit
        }
    }

    private fun applyTls(link: Link, outbound: JSONObject, default: Boolean) {
        val security = (link.query["security"] ?: "").lowercase()
        val enabled = when (security) {
            "tls", "reality", "1", "true" -> true
            "none", "0", "false" -> false
            else -> link.query["tls"]?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: default
        }
        if (!enabled) return

        val tls = JSONObject().put("enabled", true)
        val serverName = link.query["sni"] ?: link.query["peer"] ?: link.query["host"]
        if (!serverName.isNullOrBlank()) tls.put("server_name", serverName)
        if (link.query["allowinsecure"] == "1" || link.query["insecure"] == "1") tls.put("insecure", true)
        val alpn = link.query["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (!alpn.isNullOrEmpty()) tls.put("alpn", JSONArray(alpn))
        link.query["fp"]?.takeIf { it.isNotBlank() }?.let {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
        }
        val publicKey = link.query["pbk"]
        if (security == "reality" || !publicKey.isNullOrBlank()) {
            val reality = JSONObject().put("enabled", true)
            if (!publicKey.isNullOrBlank()) reality.put("public_key", publicKey)
            link.query["sid"]?.takeIf { it.isNotBlank() }?.let { reality.put("short_id", it) }
            tls.put("reality", reality)
        }
        outbound.put("tls", tls)
    }

    // --- Каркас конфига ---------------------------------------------------

    private fun assemble(outbound: JSONObject): JSONObject {
        val dns = JSONObject()
            .put(
                "servers",
                JSONArray()
                    .put(JSONObject().put("type", "udp").put("tag", "dns-direct").put("server", "8.8.8.8"))
                    .put(
                        JSONObject()
                            .put("type", "udp")
                            .put("tag", "dns-proxy")
                            .put("server", "1.1.1.1")
                            .put("detour", TAG_PROXY),
                    ),
            )
            .put("final", "dns-proxy")
            .put("strategy", "prefer_ipv4")

        val inbound = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("address", JSONArray().put(TUN_IPV4).put(TUN_IPV6))
            .put("mtu", 9000)
            .put("auto_route", true)
            .put("strict_route", false)
            .put("stack", "mixed")

        val route = JSONObject()
            .put(
                "rules",
                JSONArray()
                    .put(JSONObject().put("action", "sniff"))
                    .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns")),
            )
            .put("final", TAG_PROXY)
            .put("auto_detect_interface", true)
            .put("default_domain_resolver", JSONObject().put("server", "dns-direct"))

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(inbound))
            .put(
                "outbounds",
                JSONArray()
                    .put(outbound)
                    .put(JSONObject().put("type", "direct").put("tag", TAG_DIRECT)),
            )
            .put("route", route)
    }

    // --- Разбор ссылки ----------------------------------------------------

    private class Link(
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
    )

    private fun parse(value: String): Link? {
        val afterScheme = value.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val withoutFragment = afterScheme.substringBefore('#')
        val queryString = withoutFragment.substringAfter('?', "")
        val authority = withoutFragment.substringBefore('?')
        val userInfo = if (authority.contains('@')) authority.substringBeforeLast('@') else ""
        val hostPort = if (authority.contains('@')) authority.substringAfterLast('@') else authority
        val host = hostOf(hostPort) ?: return null
        val port = portOf(hostPort)
        if (port !in 1..65535) return null
        return Link(userInfo, host, port, queryOf(queryString))
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

    private fun portOf(hostPort: String): Int {
        val raw = if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 0) return 443
            hostPort.substring(end + 1).removePrefix(":")
        } else if (hostPort.contains(':')) {
            hostPort.substringAfterLast(':')
        } else {
            ""
        }
        return raw.toIntOrNull() ?: 443
    }

    private fun queryOf(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('=')).lowercase()
            if (key.isEmpty()) continue
            val raw = if (pair.contains('=')) pair.substringAfter('=') else ""
            result[key] = decode(raw)
        }
        return result
    }

    private fun decode(value: String): String = SubscriptionLinkParser.decodeComponent(value).trim()
}

