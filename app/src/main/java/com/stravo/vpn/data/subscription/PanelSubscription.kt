package com.stravo.vpn.data.subscription

import com.stravo.vpn.engine.box.SingBoxConfigBuilder
import com.stravo.vpn.domain.subscription.WireGuardProfile
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

    /** Reasons are fixed, safe UI text; Links contain secrets and must only enter SecretStore. */
    sealed interface Conversion {
        class Links(val links: List<String>) : Conversion
        data class Unsupported(val reason: String) : Conversion
        data object Broken : Conversion
    }

    private class UnsupportedConfig(val reason: String) : Exception()

    /** Importer can expose Unsupported.reason instead of flattening full configs into one node. */
    fun convert(body: String): Conversion = try {
        val links = decodeLinks(body)
        if (links.isEmpty()) Conversion.Broken else Conversion.Links(links)
    } catch (error: UnsupportedConfig) {
        Conversion.Unsupported(error.reason)
    } catch (error: WireGuardProfile.Unsupported) {
        Conversion.Unsupported(error.reason)
    } catch (_: Exception) {
        Conversion.Broken
    }

    /** Похоже ли тело подписки на JSON-конфиг панели. */
    fun looksLikeJson(body: String): Boolean {
        val trimmed = body.trim()
        return trimmed.startsWith("[") || trimmed.startsWith("{")
    }

    /** Compatibility API. Rejected configurations yield NO nodes; use convert for the reason. */
    fun toLinks(body: String): List<String> = (convert(body) as? Conversion.Links)?.links.orEmpty()

    private fun decodeLinks(body: String): List<String> {
        if (WireGuardProfile.looksLikeConf(body)) return WireGuardProfile.toLinks(body)
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) return xrayLinks(parseArray(trimmed) ?: return emptyList())
        val config = parseObject(trimmed) ?: return emptyList()
        // Clash/mihomo: список прокси. Xray: один конфиг с outbounds.
        if (config.has("proxies")) {
            if (listOf("rules", "proxy-groups", "rule-providers", "proxy-providers", "dns").any { nonempty(config, it) }) {
                throw UnsupportedConfig("Полный Clash-конфиг с правилами или группами не поддерживается")
            }
            return clashLinks(config)
        }
        if (config.has("outbounds")) return xrayLinks(JSONArray().put(config))
        if (config.has("endpoints") || config.has("private_key") || config.has("PrivateKey")) {
            throw UnsupportedConfig("Полный JSON endpoints не импортируется: используйте WireGuard .conf или share-ссылку")
        }
        return emptyList()
    }

    private fun nonempty(json: JSONObject, key: String): Boolean = when (val value = json.opt(key)) {
        null, JSONObject.NULL -> false
        is JSONObject -> value.length() > 0
        is JSONArray -> value.length() > 0
        is String -> value.isNotBlank()
        else -> true
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
            if (listOf("routing", "route", "dns", "fakedns", "reverse", "observatory", "burstObservatory", "endpoints", "services").any { nonempty(config, it) }) {
                throw UnsupportedConfig("Полный конфиг с DNS, маршрутизацией или цепочками не поддерживается")
            }
            val remarks = cleanName(config.optString("remarks"))
            val outbounds = config.optJSONArray("outbounds") ?: continue
            val candidates = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
                .filter { it.optString("protocol") !in setOf("freedom", "blackhole", "dns") }
            if (candidates.size != 1 || candidates.single() !== outbounds.optJSONObject(0)) {
                throw UnsupportedConfig("Конфиг с несколькими outbound нельзя заменить одним узлом")
            }
            val outbound = candidates.single()
            rejectAdvancedOutbound(outbound)
            val link = xrayLink(outbound, remarks)
                ?: throw UnsupportedConfig("Протокол или структура Xray-конфига не поддерживается")
            if (SingBoxConfigBuilder.outboundFor(link) == null) {
                throw UnsupportedConfig("Параметры узла не поддерживаются или некорректны")
            }
            if (!links.contains(link)) links.add(link)
        }
        return links
    }

    private fun rejectAdvancedOutbound(outbound: JSONObject) {
        val stream = outbound.optJSONObject("streamSettings")
        if (nonempty(outbound, "proxySettings") || nonempty(outbound, "sendThrough") ||
            outbound.optJSONObject("mux")?.optBoolean("enabled") == true ||
            stream?.let { nonempty(it, "sockopt") || nonempty(it, "finalmask") || nonempty(it, "address") || nonempty(it, "port") } == true) {
            throw UnsupportedConfig("Цепочки, mux и дополнительные сетевые параметры Xray не поддерживаются")
        }
        val settings = outbound.optJSONObject("settings") ?: return
        for (key in listOf("vnext", "servers")) {
            val servers = settings.optJSONArray(key) ?: continue
            if (servers.length() != 1 || (servers.optJSONObject(0)?.optJSONArray("users")?.length() ?: 1) != 1) {
                throw UnsupportedConfig("Несколько серверов или пользователей в одном outbound не поддерживаются")
            }
            val server = servers.getJSONObject(0)
            require(listOf("address", "port", "password", "method").none { server.has(it) && server.isNull(it) })
            server.optJSONArray("users")?.optJSONObject(0)?.let { user ->
                require(listOf("id", "user", "pass", "encryption", "flow", "security", "alterId")
                    .none { user.has(it) && user.isNull(it) })
            }
        }
        val tls = stream?.optJSONObject("tlsSettings")
        if (tls != null && listOf("certificates", "pinnedPeerCertificateChainSha256", "verifyPeerCertByName", "echConfigList").any { nonempty(tls, it) }) {
            throw UnsupportedConfig("Дополнительная проверка TLS-сертификата не поддерживается")
        }
        val reality = stream?.optJSONObject("realitySettings")
        if (reality != null && nonempty(reality, "mldsa65Verify")) {
            throw UnsupportedConfig("REALITY ML-DSA verify не поддерживается этим конвертером")
        }
        for (key in listOf("wsSettings", "httpUpgradeSettings")) {
            val transport = stream?.optJSONObject(key) ?: continue
            rejectExtraHeaders(transport.optJSONObject("headers"))
            if (transport.optBoolean("useBrowserForwarding")) {
                throw UnsupportedConfig("Browser forwarding не поддерживается")
            }
        }
    }

    private fun xrayLink(outbound: JSONObject, name: String?): String? {
        val protocol = outbound.optString("protocol").lowercase()
        val settings = outbound.optJSONObject("settings") ?: return null
        val stream = outbound.optJSONObject("streamSettings")
        return when (protocol) {
            "vless", "vmess" -> {
                val server = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                val address = server.optString("address").takeIf { it.isNotBlank() } ?: return null
                val user = server.optJSONArray("users")?.optJSONObject(0) ?: return null
                val id = user.optString("id").takeIf { it.isNotBlank() } ?: return null
                val params = xrayStreamParams(stream)
                if (protocol == "vless") {
                    user.optString("flow").takeIf { it.isNotBlank() }?.let { params["flow"] = it }
                    user.optString("encryption").takeIf { it.isNotBlank() }?.let { params["encryption"] = it }
                } else {
                    user.optString("security").takeIf { it.isNotBlank() }?.let { params["scy"] = it }
                    user.optString("alterId").takeIf { it.isNotBlank() }?.let { params["aid"] = it }
                }
                buildLink(protocol, percentEncode(id) + "@" + authority(address, portOf(server)), params, name)
            }

            "trojan" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                val address = server.optString("address").takeIf { it.isNotBlank() } ?: return null
                val password = server.optString("password").takeIf { it.isNotBlank() } ?: return null
                buildLink("trojan", percentEncode(password) + "@" + authority(address, portOf(server)), xrayStreamParams(stream), name)
            }

            "socks", "http" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                val address = server.optString("address").takeIf { it.isNotBlank() } ?: return null
                val user = server.optJSONArray("users")?.optJSONObject(0)
                val credentials = if (user != null) percentEncode(user.optString("user")) + ":" + percentEncode(user.optString("pass")) + "@" else ""
                val scheme = if (protocol == "socks") "socks5" else if (stream?.optString("security") == "tls") "https" else "http"
                buildLink(scheme, credentials + authority(address, portOf(server, if (protocol == "socks") 1080 else if (scheme == "https") 443 else 80)), xrayStreamParams(stream), name)
            }

            "shadowsocks" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                val address = server.optString("address").takeIf { it.isNotBlank() } ?: return null
                val method = server.optString("method").takeIf { it.isNotBlank() } ?: return null
                ssLink(method, server.optString("password"), address, portOf(server, 8388), name)
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
        // Xray renamed publicKey to password; the current field wins when both are supplied.
        (reality?.optString("password")?.takeIf { it.isNotBlank() && it != NULL_TEXT }
            ?: reality?.optString("publicKey")?.takeIf { it.isNotBlank() && it != NULL_TEXT })
            ?.let { params["pbk"] = it }
        reality?.optString("shortId")?.takeIf { it.isNotBlank() }?.let { params["sid"] = it }
        reality?.optString("spiderX")?.takeIf { it.isNotBlank() }?.let { params["spx"] = it }
        if (tls?.optBoolean("allowInsecure") == true) params["allowinsecure"] = "1"
        tls?.optJSONArray("alpn")?.let { alpn ->
            (0 until alpn.length()).mapNotNull { index ->
                alpn.optString(index).takeIf { it.isNotBlank() && it != NULL_TEXT }
            }.joinToString(",").takeIf { it.isNotBlank() }?.let { params["alpn"] = it }
        }

        when (stream.optString("network").lowercase()) {
            "tcp", "raw" -> {
                val tcp = stream.optJSONObject("rawSettings") ?: stream.optJSONObject("tcpSettings")
                tcp?.optJSONObject("header")?.optString("type")?.takeIf { it.isNotBlank() }
                    ?.let { params["headerType"] = it }
            }
            "ws" -> {
                val ws = stream.optJSONObject("wsSettings")
                ws?.optString("path")?.takeIf { it.isNotBlank() }?.let { params["path"] = it }
                ws?.optString("host")?.takeIf { it.isNotBlank() }?.let { params["host"] = it }
                ws?.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotBlank() }?.let { params["host"] = it }
            }

            "httpupgrade" -> {
                val upgrade = stream.optJSONObject("httpUpgradeSettings")
                upgrade?.optString("path")?.takeIf { it.isNotBlank() }?.let { params["path"] = it }
                upgrade?.optString("host")?.takeIf { it.isNotBlank() }?.let { params["host"] = it }
                upgrade?.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotBlank() }?.let { params["host"] = it }
            }

            "http", "h2" -> {
                val http = stream.optJSONObject("httpSettings")
                http?.optString("path")?.takeIf { it.isNotBlank() }?.let { params["path"] = it }
                http?.optJSONArray("host")?.let { hosts ->
                    params["host"] = (0 until hosts.length()).joinToString(",") { hosts.getString(it) }
                }
            }

            "grpc" -> {
                val grpc = stream.optJSONObject("grpcSettings")
                grpc?.optString("serviceName")?.takeIf { it.isNotBlank() }?.let { params["serviceName"] = it }
                if (grpc?.optBoolean("multiMode") == true) params["mode"] = "multi"
                grpc?.optString("authority")?.takeIf { it.isNotBlank() }?.let { params["authority"] = it }
            }

            "xhttp", "splithttp" -> {
                val xhttp = stream.optJSONObject("xhttpSettings") ?: stream.optJSONObject("splithttpSettings")
                // XHTTP за CDN держится на тонких параметрах: режим packet-up, метод
                // отправки (панель ставит GET, потому что CDN пропускает GET/HEAD/
                // OPTIONS), размещение session и seq в query. Панель кладёт их в
                // extra (JSON строкой) и дублирует плоскими полями.
                //
                // Раньше в ссылку попадали только path, host и mode: остальное
                // терялось, ядро уходило на умолчания (uplink POST, session в пути),
                // и узел за CDN отвечал 405 Method Not Allowed — туннель поднимался,
                // а трафик не шёл. Теперь extra переносится как есть, а плоские поля —
                // по таблице имён ниже.
                xhttp?.optString("extra")?.takeIf { it.isNotBlank() && it != NULL_TEXT }
                    ?.let { params["extra"] = it }
                for ((panelKey, linkKey) in XHTTP_LINK_PARAMS) {
                    xhttp?.optString(panelKey)?.takeIf { it.isNotBlank() && it != NULL_TEXT }
                        ?.let { params[linkKey] = it }
                }
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
            require(listOf("server", "port", "uuid", "username", "password", "cipher", "method")
                .none { proxy.has(it) && proxy.isNull(it) })
            if (listOf("dialer-proxy", "plugin", "smux", "certificate", "fingerprint").any { nonempty(proxy, it) }) {
                throw UnsupportedConfig("Цепочки, плагины и дополнительные параметры Clash не поддерживаются")
            }
            val link = clashLink(proxy) ?: throw UnsupportedConfig("Протокол Clash не поддерживается")
            if (SingBoxConfigBuilder.outboundFor(link) == null) {
                throw UnsupportedConfig("Параметры узла не поддерживаются или некорректны")
            }
            if (!links.contains(link)) links.add(link)
        }
        return links
    }

    private fun clashLink(proxy: JSONObject): String? {
        val type = proxy.optString("type").lowercase()
        val address = proxy.optString("server").takeIf { it.isNotBlank() } ?: return null
        val port = portOf(proxy)
        val name = cleanName(proxy.optString("name"))
        return when (type) {
            "vless", "vmess" -> {
                val uuid = proxy.optString("uuid").takeIf { it.isNotBlank() } ?: return null
                val params = clashParams(proxy)
                if (type == "vless") {
                    proxy.optString("flow").takeIf { it.isNotBlank() }?.let { params["flow"] = it }
                    proxy.optString("encryption").takeIf { it.isNotBlank() }?.let { params["encryption"] = it }
                } else {
                    proxy.optString("cipher").takeIf { it.isNotBlank() }?.let { params["scy"] = it }
                    proxy.optString("alterId").takeIf { it.isNotBlank() }?.let { params["aid"] = it }
                }
                buildLink(type, percentEncode(uuid) + "@" + authority(address, port), params, name)
            }

            "trojan" -> {
                val password = proxy.optString("password").takeIf { it.isNotBlank() } ?: return null
                buildLink("trojan", percentEncode(password) + "@" + authority(address, port), clashParams(proxy), name)
            }

            "anytls" -> {
                val password = proxy.optString("password").takeIf { it.isNotBlank() } ?: return null
                buildLink("anytls", percentEncode(password) + "@" + authority(address, port), clashParams(proxy), name)
            }

            "hysteria2", "hy2" -> {
                val password = proxy.optString("password").takeIf { it.isNotBlank() } ?: return null
                val params = clashParams(proxy)
                proxy.optString("obfs").takeIf { it.isNotBlank() }?.let { params["obfs"] = it }
                proxy.optString("obfs-password").takeIf { it.isNotBlank() }?.let { params["obfs-password"] = it }
                buildLink("hysteria2", percentEncode(password) + "@" + authority(address, port), params, name)
            }

            "tuic" -> {
                val uuid = proxy.optString("uuid").takeIf { it.isNotBlank() } ?: return null
                val params = clashParams(proxy)
                for ((source, target) in mapOf(
                    "congestion-controller" to "congestion_control", "udp-relay-mode" to "udp_relay_mode",
                    "reduce-rtt" to "zero_rtt_handshake", "disable-sni" to "disable_sni",
                )) proxy.optString(source).takeIf { it.isNotBlank() }?.let { params[target] = it }
                buildLink("tuic", percentEncode(uuid) + ":" + percentEncode(proxy.optString("password")) + "@" + authority(address, port), params, name)
            }

            "socks5", "http" -> {
                val username = proxy.optString("username")
                val credentials = if (username.isNotEmpty()) percentEncode(username) + ":" + percentEncode(proxy.optString("password")) + "@" else ""
                val scheme = if (type == "http" && proxy.optBoolean("tls")) "https" else type
                buildLink(scheme, credentials + authority(address, port), clashParams(proxy), name)
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
        proxy.optJSONArray("alpn")?.let { alpn ->
            params["alpn"] = (0 until alpn.length()).joinToString(",") { alpn.getString(it) }
        }

        when (network.lowercase()) {
            "ws" -> {
                val ws = proxy.optJSONObject("ws-opts")
                rejectExtraHeaders(ws?.optJSONObject("headers"))
                if (ws?.has("max-early-data") == true || ws?.has("early-data-header-name") == true) {
                    throw UnsupportedConfig("WebSocket early data в JSON не поддерживается этим конвертером")
                }
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

            "http", "h2" -> {
                val http = proxy.optJSONObject("h2-opts") ?: proxy.optJSONObject("http-opts")
                http?.optString("path")?.takeIf { it.isNotBlank() }?.let { params["path"] = it }
                http?.optJSONArray("host")?.let { hosts ->
                    params["host"] = (0 until hosts.length()).joinToString(",") { hosts.getString(it) }
                }
            }

            "xhttp", "splithttp" -> throw UnsupportedConfig("Clash XHTTP: требуется исходная share-ссылка или JSON Xray")
        }
        return params
    }

    // --- Сборка строки ----------------------------------------------------

    private fun rejectExtraHeaders(headers: JSONObject?) {
        if (headers != null && headers.keys().asSequence().any { it != "Host" }) {
            throw UnsupportedConfig("Дополнительные заголовки транспорта не поддерживаются этим конвертером")
        }
    }

    private fun portOf(json: JSONObject, fallback: Int = 443): Int {
        if (!json.has("port")) return fallback
        val port = json.opt("port")?.toString()?.toIntOrNull() ?: throw IllegalArgumentException()
        require(port in 1..65535)
        return port
    }

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

    /**
     * Поля xhttpSettings (имена Xray/панели, слева) → параметры share-ссылки,
     * которые разбирает SingBoxConfigBuilder (имена ядра sing-box-lx, справа;
     * см. SPECS/TASKS/002-XHTTP_CLIENT_TRANSPORT/URL_PARSING.md).
     *
     * Без этого переноса ссылка теряет всё, кроме path/host/mode, и ядро берёт
     * умолчания: uplink POST и session в пути — узел за CDN отвечает 405.
     */
    private val XHTTP_LINK_PARAMS = listOf(
        "path" to "path",
        "host" to "host",
        "mode" to "mode",
        "uplinkHTTPMethod" to "uplinkHTTPMethod",
        "uplinkDataPlacement" to "uplinkDataPlacement",
        "uplinkDataKey" to "uplinkDataKey",
        "uplinkChunkSize" to "uplinkChunkSize",
        "sessionIDPlacement" to "sessionPlacement",
        "sessionIDKey" to "sessionKey",
        "sessionIDLength" to "sessionLength",
        "sessionIDTable" to "sessionTable",
        "seqPlacement" to "seqPlacement",
        "seqKey" to "seqKey",
        "xPaddingBytes" to "xPaddingBytes",
        "noGRPCHeader" to "noGRPCHeader",
        "xPaddingObfsMode" to "xPaddingObfsMode",
        "xPaddingKey" to "xPaddingKey",
        "xPaddingHeader" to "xPaddingHeader",
        "xPaddingPlacement" to "xPaddingPlacement",
        "xPaddingMethod" to "xPaddingMethod",
        "scMaxEachPostBytes" to "scMaxEachPostBytes",
        "scMinPostsIntervalMs" to "scMinPostsIntervalMs",
    )

    /** org.json отдаёт отсутствующее значение строкой "null": это не значение. */
    private const val NULL_TEXT = "null"

    private val PROTOCOL_WORDS = setOf(
        "vless", "vmess", "trojan", "shadowsocks", "ss", "hysteria", "hysteria2", "hy2",
        "anytls", "tuic", "naive", "wireguard", "xhttp", "reality", "tls",
    )

    private const val NAME_LIMIT = 60
    private const val HEX = "0123456789abcdef"
}

