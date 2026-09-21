package com.stravo.vpn.domain.subscription

import org.json.JSONObject

/** Offline wire-format decoding shared by import and the core builder. Never log this object. */
internal class ProxyShareLink(
    val scheme: String,
    val userInfo: String,
    val host: String,
    val port: Int,
    val query: Map<String, String>,
    val name: String? = null,
) {
    companion object {
        // v2rayN VmessFmt: classic base64 JSON and the UUID@host share-link format.
        fun parse(raw: String): ProxyShareLink? = runCatching { parseUnsafe(raw.trim()) }.getOrNull()

        private fun parseUnsafe(value: String): ProxyShareLink? {
            if (value.length > 65536 || !value.contains("://") || value.any { it.isWhitespace() }) return null
            val scheme = value.substringBefore("://").lowercase()
            val body = value.substringAfter("://")
            val fragment = body.substringAfter('#', "")
            val name = (if (scheme in setOf("wireguard", "wg")) fragment.substringBefore('?') else fragment)
                .takeIf { it.isNotEmpty() }?.let(::decode)
            val content = body.substringBefore('#')
            if (scheme == "vmess" && !content.contains('@')) {
                val json = Base64Codec.decodeOrNull(decode(content)) ?: return null
                return vmessJson(JSONObject(json), name)
            }
            val query = linkedMapOf<String, String>()
            for (pair in content.substringAfter('?', "").split('&')) {
                if (pair.isEmpty()) continue
                val key = decode(pair.substringBefore('=')).lowercase()
                val decoded = decode(pair.substringAfter('=', ""))
                if (query.containsKey(key) && query[key] != decoded) return null
                query[key] = decoded
            }
            var authority = content.substringBefore('?')
            if (scheme == "ss" && !authority.contains('@')) {
                authority = Base64Codec.decodeOrNull(decode(authority)) ?: return null
            }
            var credentials = if (authority.contains('@')) authority.substringBeforeLast('@') else ""
            val hostPort = authority.substringAfterLast('@').removeSuffix("/")
            val host: String
            val portText: String?
            if (hostPort.startsWith('[')) {
                val end = hostPort.indexOf(']')
                if (end <= 1) return null
                host = hostPort.substring(1, end)
                val suffix = hostPort.substring(end + 1)
                if (suffix.isNotEmpty() && !suffix.startsWith(':')) return null
                portText = if (suffix.isEmpty()) null else suffix.substring(1)
            } else {
                if (hostPort.count { it == ':' } > 1) return null
                host = hostPort.substringBefore(':')
                portText = if (hostPort.contains(':')) hostPort.substringAfter(':') else null
            }
            if (!validHost(host)) return null
            val port = if (portText == null) when (scheme) {
                "http" -> 80
                "socks", "socks5" -> 1080
                "wg", "wireguard" -> return null // Endpoint port is required by the share-link contract.
                else -> 443
            } else portText.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            if (scheme == "vmess") {
                if (!credentials.contains(':') && !isUuid(decode(credentials))) {
                    credentials = Base64Codec.decodeOrNull(decode(credentials)) ?: return null
                }
                if (credentials.contains(':')) {
                    if (!query.containsKey("scy")) query["scy"] = decode(credentials.substringBefore(':'))
                    credentials = credentials.substringAfter(':')
                }
            }
            if (scheme in setOf("ss", "socks", "socks5") && credentials.isNotEmpty() && !credentials.contains(':')) {
                val decoded = Base64Codec.decodeOrNull(decode(credentials)) ?: return null
                if (!decoded.contains(':')) return null
                credentials = encode(decoded.substringBefore(':')) + ":" + encode(decoded.substringAfter(':'))
            }
            return ProxyShareLink(scheme, credentials, host, port, query, name)
        }

        private fun vmessJson(json: JSONObject, name: String?): ProxyShareLink? {
            fun text(key: String) = json.optString(key).takeUnless { it.isEmpty() || it == "null" }
            val host = text("add") ?: return null
            val port = text("port")?.toIntOrNull() ?: return null
            val uuid = text("id") ?: return null
            if (!validHost(host) || port !in 1..65535 || !isUuid(uuid)) return null
            val query = linkedMapOf<String, String>()
            for ((source, target) in mapOf(
                "net" to "type", "tls" to "security", "aid" to "aid", "scy" to "scy",
                "sni" to "sni", "fp" to "fp", "alpn" to "alpn", "host" to "host",
                "path" to "path", "pbk" to "pbk", "sid" to "sid", "insecure" to "insecure",
                "allowInsecure" to "allowinsecure", "packetEncoding" to "packetencoding",
                "extra" to "extra", "pcs" to "pcs", "vcn" to "vcn",
            )) text(source)?.let { query[target] = it }
            when (query["type"]?.lowercase()) {
                "grpc" -> {
                    text("path")?.let { query["servicename"] = it }
                    text("type")?.let { query["mode"] = it }
                }
                "xhttp", "splithttp" -> text("type")?.let { query["mode"] = it }
                else -> text("type")?.let { query["headertype"] = it }
            }
            return ProxyShareLink("vmess", uuid, host, port, query, name ?: text("ps"))
        }

        fun isUuid(value: String): Boolean =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)

        private fun validHost(host: String): Boolean = host.isNotEmpty() &&
            host.none { it.isWhitespace() || it.isISOControl() || it in "/?#@%[]\\" }

        // Share links are URIs, not form data: '+' is part of a password or key.
        fun decode(value: String): String {
            var index = value.indexOf('%')
            while (index >= 0) {
                require(index + 2 < value.length && value.substring(index + 1, index + 3).toIntOrNull(16) != null)
                index = value.indexOf('%', index + 3)
            }
            return java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        }

        private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
