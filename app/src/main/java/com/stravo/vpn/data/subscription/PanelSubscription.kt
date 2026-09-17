package com.stravo.vpn.data.subscription

import org.json.JSONArray
import org.json.JSONObject

/**
 * Панели отдают подписку по-разному: списком ссылок, base64 от него, массивом конфигов
 * Xray (?format=xray), конфигом Clash/mihomo (?format=mihomo) или голым ключом.
 *
 * Декодер приводит JSON-варианты к обычным share-ссылкам, чтобы дальше работал один и тот же
 * путь: SubscriptionLinkParser → SecretStore → ядро sing-box. Хосты и ключи остаются внутри
 * ссылки и наружу (в UI, состояние и логи) не попадают.
 */
object PanelSubscription {

    /** Похоже ли тело подписки на JSON-конфиг панели. */
    fun looksLikeJson(body: String): Boolean {
        val trimmed = body.trim()
        return trimmed.startsWith("[") || trimmed.startsWith("{")
    }

    /** Ссылки узлов из JSON-конфига. Пустой список — формат не распознан. */
    fun toLinks(body: String): List<String> {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) return xrayLinks(parseArray(trimmed) ?: return emptyList())
        val config = parseObject(trimmed) ?: return emptyList()
        // Clash/mihomo: список прокси. Xray: один конфиг с outbounds.
        if (config.has("proxies")) return clashLinks(config)
        if (config.has("outbounds")) return xrayLinks(JSONArray().put(config))
        return emptyList()
    }

    private fun parseArray(text: String): JSONArray? = try {
        JSONArray(text)
    } catch (error: Exception) {
        null
    }

    private fun parseObject(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (error: Exception) {
        null
    }

    // --- Xray: массив конфигов --------------------------------------------

    private fun xrayLinks(configs: JSONArray): List<String> {
        val links = ArrayList<String>()
        for (index in 0 until configs.length()) {
            val config = configs.optJSONObject(index) ?: continue
            val remarks = cleanName(config.optString("remarks"))
            val outbounds = config.optJSONArray("outbounds") ?: continue
            for (position in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(position) ?: continue
                val link = xrayLink(outbound, remarks) ?: continue
                if (!links.contains(link)) links.add(link)
                break
            }
        }
        return links
    }

    private fun xrayLink(outbound: JSONObject, name: String?): String? {
        val protocol = outbound.optString("protocol").lowercase()
        val settings = outbound.optJSONObject("settings") ?: return null
        val stream = outbound.optJSONObject("streamSettings")
        return when (protocol) {
            "vless" -> {
                val server = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                val address = server.optString("address").takeIf { it.isNotBlank() } ?: return null
                val user = server.optJSONArray("users")?.optJSONObject(0) ?: return null
                val id = user.optString("id").takeIf { it.isNotBlank() } ?: return null
                val params = xrayStreamParams(stream)
                user.optString("flow").takeIf { it.isNotBlank() }?.let { params["flow"] = it }
                user.optString("encryption").takeIf { it.isNotBlank() }?.let { params["encryption"] = it }
                buildLink("vless", id + "@" + authority(address, server.optInt("port", 443)), params, name)
            }

            "trojan" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                val address = server.optString("address").takeIf { it.isNotBlank() } ?: return null
                val password = server.optString("password").takeIf { it.isNotBlank() } ?: return null
                buildLink("trojan", password + "@" + authority(address, server.optInt("port", 443)), xrayStreamParams(stream), name)
            }

            "shadowsocks" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                val address = server.optString("address").takeIf { it.isNotBlank() } ?: return null
                val method = server.optString("method").takeIf { it.isNotBlank() } ?: return null
                ssLink(method, server.optString("password"), address, server.optInt("port", 8388), name)
            }

            else -> null
        }
    }

    private fun xrayStreamParams(stream: JSONObject?): LinkedHashMap<String, String> {
        val params = LinkedHashMap<String, String>()
        if (stream == null) return params
        stream.optString("network").takeIf { it.isNotBlank() }?.let { params["type"] = it }
        stream.optString("security").takeIf { it.isNotBlank() }?.let { params["security"] = it }
        val reality = stream.optJSONObject("realitySettings")
        val tls = stream.optJSONObject("tlsSettings")
        val serverName = reality?.optString("serverName")?.takeIf { it.isNotBlank() }
            ?: tls?.optString("serverName")?.takeIf { it.isNotBlank() }
        if (serverName != null) params["sni"] = serverName
        val fingerprint = reality?.optString("fingerprint")?.takeIf { it.isNotBlank() }
            ?: tls?.optString("fingerprint")?.takeIf { it.isNotBlank() }
        if (fingerprint != null) params["fp"] = fingerprint
        reality?.optString("publicKey")?.takeIf { it.isNotBlank() }?.let { params["pbk"] = it }
        reality?.optString("shortId")?.takeIf { it.isNotBlank() }?.let { params["sid"] = it }
        reality?.optString("spiderX")?.takeIf { it.isNotBlank() }?.let { params["spx"] = it }
        if (tls?.optBoolean("allowInsecure") == true) params["allowinsecure"] = "1"

        when (stream.optString("network").lowercase()) {
            "ws" -> {
                val ws = stream.optJSONObject("wsSettings")
                ws?.optString("path")?.takeIf { it.isNotBlank() }?.let { params["path"] = it }
                ws?.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotBlank() }?.let { params["host"] = it }
            }

            "httpupgrade" -> {
                val upgrade = stream.optJSONObject("httpUpgradeSettings")
                upgrade?.optString("path")?.takeIf { it.isNotBlank() }?.let { params["path"] = it }
                upgrade?.optString("host")?.takeIf { it.isNotBlank() }?.let { params["host"] = it }
            }

            "grpc" -> stream.optJSONObject("grpcSettings")?.optString("serviceName")
                ?.takeIf { it.isNotBlank() }?.let { params["serviceName"] = it }

            "xhttp", "splithttp" -> {
                val xhttp = stream.optJSONObject("xhttpSettings")
                xhttp?.optString("path")?.takeIf { it.isNotBlank() }?.let { params["path"] = it }
                xhttp?.optString("host")?.takeIf { it.isNotBlank() }?.let { params["host"] = it }
                xhttp?.optString("mode")?.takeIf { it.isNotBlank() }?.let { params["mode"] = it }
            }
        }
        return params
    }

    // --- Clash / mihomo: список прокси ------------------------------------

    private fun clashLinks(config: JSONObject): List<String> {
        val proxies = config.optJSONArray("proxies") ?: return emptyList()
        val links = ArrayList<String>()
        for (index in 0 until proxies.length()) {
            val proxy = proxies.optJSONObject(index) ?: continue
            val link = clashLink(proxy) ?: continue
            if (!links.contains(link)) links.add(link)
        }
        return links
    }

    private fun clashLink(proxy: JSONObject): String? {
        val type = proxy.optString("type").lowercase()
        val address = proxy.optString("server").takeIf { it.isNotBlank() } ?: return null
        val port = proxy.optInt("port", 443)
        val name = cleanName(proxy.optString("name"))
        return when (type) {
            "vless" -> {
                val uuid = proxy.optString("uuid").takeIf { it.isNotBlank() } ?: return null
                val params = clashParams(proxy)
                proxy.optString("flow").takeIf { it.isNotBlank() }?.let { params["flow"] = it }
                buildLink("vless", uuid + "@" + authority(address, port), params, name)
            }

            "trojan" -> {
                val password = proxy.optString("password").takeIf { it.isNotBlank() } ?: return null
                buildLink("trojan", password + "@" + authority(address, port), clashParams(proxy), name)
            }

            "anytls" -> {
                val password = proxy.optString("password").takeIf { it.isNotBlank() } ?: return null
                buildLink("anytls", password + "@" + authority(address, port), clashParams(proxy), name)
            }

            "hysteria2", "hy2" -> {
                val password = proxy.optString("password").takeIf { it.isNotBlank() } ?: return null
                val params = clashParams(proxy)
                proxy.optString("obfs").takeIf { it.isNotBlank() }?.let { params["obfs"] = it }
                proxy.optString("obfs-password").takeIf { it.isNotBlank() }?.let { params["obfs-password"] = it }
                buildLink("hysteria2", password + "@" + authority(address, port), params, name)
            }

            "ss" -> {
                val method = (proxy.optString("cipher").ifBlank { proxy.optString("method") })
                    .takeIf { it.isNotBlank() } ?: return null
                ssLink(method, proxy.optString("password"), address, port, name)
            }

            else -> null
        }
    }

    private fun clashParams(proxy: JSONObject): LinkedHashMap<String, String> {
        val params = LinkedHashMap<String, String>()
        val network = proxy.optString("network")
        if (network.isNotBlank()) params["type"] = network
        val reality = proxy.optJSONObject("reality-opts")
        when {
            reality != null -> params["security"] = "reality"
            proxy.optBoolean("tls") -> params["security"] = "tls"
        }
        proxy.optString("servername").takeIf { it.isNotBlank() }?.let { params["sni"] = it }
        proxy.optString("sni").takeIf { it.isNotBlank() }?.let { params["sni"] = it }
        proxy.optString("client-fingerprint").takeIf { it.isNotBlank() }?.let { params["fp"] = it }
        reality?.optString("public-key")?.takeIf { it.isNotBlank() }?.let { params["pbk"] = it }
        reality?.optString("short-id")?.takeIf { it.isNotBlank() }?.let { params["sid"] = it }
        if (proxy.optBoolean("skip-cert-verify")) params["allowinsecure"] = "1"

        when (network.lowercase()) {
            "ws" -> {
                val ws = proxy.optJSONObject("ws-opts")
                ws?.optString("path")?.takeIf { it.isNotBlank() }?.let { params["path"] = it }
                ws?.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotBlank() }?.let { params["host"] = it }
            }

            "grpc" -> proxy.optJSONObject("grpc-opts")?.optString("grpc-service-name")
                ?.takeIf { it.isNotBlank() }?.let { params["serviceName"] = it }

            "httpupgrade" -> {
                val upgrade = proxy.optJSONObject("http-upgrade-opts")
                upgrade?.optString("path")?.takeIf { it.isNotBlank() }?.let { params["path"] = it }
                upgrade?.optString("host")?.takeIf { it.isNotBlank() }?.let { params["host"] = it }
            }
        }
        return params
    }

    // --- Сборка строки ----------------------------------------------------

    private fun ssLink(method: String, password: String, address: String, port: Int, name: String?): String =
        "ss://" + method + ":" + percentEncode(password) + "@" + authority(address, port) + fragment(name)

    private fun buildLink(
        scheme: String,
        authority: String,
        params: Map<String, String>,
        name: String?,
    ): String {
        val query = params.entries.joinToString("&") { percentEncode(it.key) + "=" + percentEncode(it.value) }
        return scheme + "://" + authority + (if (query.isEmpty()) "" else "?" + query) + fragment(name)
    }

    private fun fragment(name: String?): String =
        if (name.isNullOrBlank()) "" else "#" + percentEncode(name)

    private fun authority(address: String, port: Int): String =
        if (address.contains(':')) "[" + address + "]:" + port else address + ":" + port

    /** Имя сервера из панели: «Испания · VLESS» → «Испания». */
    private fun cleanName(raw: String): String? {
        val value = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        val parts = value.split('·', '|').map { it.trim() }.filter { it.isNotEmpty() }
        val kept = parts.filterNot { it.lowercase() in PROTOCOL_WORDS }
        return (if (kept.isEmpty()) parts else kept).joinToString(" · ").take(NAME_LIMIT)
    }

    private fun percentEncode(value: String): String {
        val builder = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            val plain = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '_' || char == '.' || char == '~'
            if (plain) {
                builder.append(char)
            } else {
                builder.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
            }
        }
        return builder.toString()
    }

    private val PROTOCOL_WORDS = setOf(
        "vless", "vmess", "trojan", "shadowsocks", "ss", "hysteria", "hysteria2", "hy2",
        "anytls", "tuic", "naive", "wireguard", "xhttp", "reality", "tls",
    )

    private const val NAME_LIMIT = 60
    private const val HEX = "0123456789abcdef"
}

