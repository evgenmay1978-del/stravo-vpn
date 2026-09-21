package com.stravo.vpn.engine.box

import com.stravo.vpn.domain.model.ProtocolCatalog
import com.stravo.vpn.domain.model.VpnTransport
import com.stravo.vpn.domain.subscription.ProxyShareLink
import com.stravo.vpn.domain.subscription.WireGuardProfile
import com.stravo.vpn.domain.subscription.ProxyShareLink as Link
import org.json.JSONArray
import org.json.JSONObject

/** Результат сборки конфига для ядра. Секреты остаются внутри [Ready.json]. */
sealed interface CoreConfig {

    /** Конфиг готов: [json] уходит только в ядро. */
    data class Ready(val json: String) : CoreConfig

    /** Транспорт разобран в подписке, но эта сборка ядра его не умеет. */
    data class Unsupported(val transport: String) : CoreConfig

    /** Ссылку не удалось разобрать: отдавать ядру нечего. */
    data object Broken : CoreConfig
}

/**
 * Собирает JSON sing-box из ссылки узла подписки.
 *
 * Поддерживаются VLESS (TCP, XHTTP, WebSocket, HTTP Upgrade, gRPC, QUIC), AnyTLS,
 * Hysteria2, Trojan, Shadowsocks, VMess, SOCKS5, HTTP CONNECT и TUIC v5.
 * WireGuard использует endpoints; AWG/full Xray не конвертируются.
 * Если транспорта нет
 * в сборке ядра, возвращается честный [CoreConfig.Unsupported], а не тихий отказ.
 *
 * Конфиг никогда не логируется и не попадает в UI-состояние.
 */
object SingBoxConfigBuilder {

    private const val TAG_PROXY = "proxy"
    private const val TAG_DIRECT = "direct"
    private const val TAG_SOCKS = "socks-in"
    private const val LOCAL_PROXY_PORT = 10808
    private const val LOOPBACK = "127.0.0.1"

    /**
     * Порт локального API ядра (Clash API) — только петлевой адрес. Через него
     * приложение берёт пинг и скорость для главного экрана: `GET /traffic` отдаёт
     * байты в секунду, `GET /proxies/proxy/delay` — задержку узла.
     */
    const val CLASH_API_PORT = 19090
    /**
     * Единственный отпечаток uTLS 1.8.7 с гибридной долей ключа X25519MLKEM768.
     * Без неё современный REALITY-сервер считает клиента «странным» и не пускает.
     */
    private const val REALITY_FINGERPRINT = "chrome"
    private const val TUN_MTU = 1500
    private const val TUN_IPV4 = "172.19.0.1/30"
    private const val TUN_IPV6 = "fdfe:dcba:9876::1/126"

    /** Only constant labels are exposed to UI; never echo a link or its parameter values. */
    private class UnsupportedOption(val label: String) : Exception()

    /**
     * [variant] выбирает диагностический вариант сборки: когда туннель поднимается,
     * а трафик не идёт, причину ищут перебором (см. [CoreVariant]).
     *
     * [directMode] пускает трафик туннеля напрямую, без узла: так проверяют сам TUN,
     * DNS и маршруты, когда узел под подозрением.
     *
     * [apiSecret] — ключ локального API метрик; null означает «собрать конфиг без
     * API вовсе». Это не украшение: ядро, собранное без тега `with_clash_api`,
     * отвергает конфиг с блоком `experimental.clash_api` целиком, вместе с узлом
     * и TUN, — поэтому метрики никогда не должны быть условием запуска (см.
     * [com.stravo.vpn.engine.box.StravoVpnService]).
     */
    fun build(
        link: String,
        variant: CoreVariant = CoreVariant.GVISOR,
        directMode: Boolean = false,
        apiSecret: String? = null,
    ): CoreConfig {
        return try {
            val wireguard = WireGuardProfile.isLink(link)
            val node = if (wireguard) WireGuardProfile.endpoint(link, TAG_PROXY) else createOutbound(link)
            if (node == null) return CoreConfig.Broken
            val config = assemble(node, variant, directMode, apiSecret, endpoint = wireguard)
            if (wireguard) {
                val servers = WireGuardProfile.dnsFor(link)
                if (servers.isNotEmpty()) {
                    val dns = config.getJSONObject("dns")
                    val existing = dns.getJSONArray("servers")
                    val configured = JSONArray().put(existing.getJSONObject(0))
                    val detour = if (directMode || variant == CoreVariant.DIRECT_RESOLVER) TAG_DIRECT else TAG_PROXY
                    servers.forEachIndexed { index, address ->
                        configured.put(JSONObject().put("type", "udp")
                            .put("tag", if (index == 0) "dns-proxy" else "dns-wg-$index")
                            .put("server", address).put("detour", detour))
                    }
                    dns.put("servers", configured)
                }
            }
            CoreConfig.Ready(config.toString())
        } catch (error: UnsupportedOption) {
            CoreConfig.Unsupported(error.label)
        } catch (error: WireGuardProfile.Unsupported) {
            CoreConfig.Unsupported(error.reason)
        } catch (_: Exception) {
            CoreConfig.Broken
        }
    }

    /** A standalone outbound for a selector/urltest group; null means unsupported or invalid.
     * Contains secrets: pass only to the core. WireGuard endpoints must never be placed here.
     */
    fun outboundFor(link: String, tag: String = "proxy"): JSONObject? = runCatching {
        require(tag.isNotBlank())
        if (WireGuardProfile.isLink(link)) return@runCatching null
        createOutbound(link)?.put("tag", tag)
    }.getOrNull()

    /** Secret-bearing WireGuard endpoint. Put in endpoints[], never in outbounds[]. */
    fun endpointFor(link: String, tag: String = "proxy"): JSONObject? = runCatching {
        if (!WireGuardProfile.isLink(link)) return@runCatching null
        WireGuardProfile.endpoint(link, tag)
    }.getOrNull()

    private fun createOutbound(value: String): JSONObject? {
        val scheme = value.trim().substringBefore("://", "").lowercase()
        when {
            value.trimStart().startsWith('{') || value.trimStart().startsWith('[') ->
                throw UnsupportedOption("Полный JSON-конфиг")
            scheme.isEmpty() -> return null
            scheme in setOf("wg", "wireguard") -> return null
            scheme in setOf("awg", "amneziawg", "vpn") -> throw UnsupportedOption("AmneziaWG")
            scheme !in setOf("vless", "vmess", "trojan", "hysteria2", "hy2", "anytls", "ss", "socks", "socks5", "http", "https", "tuic") ->
                throw UnsupportedOption("Протокол")
        }
        val link = ProxyShareLink.parse(value) ?: return null
        val protocol = ProtocolCatalog.byScheme(scheme) ?: return null
        val transport = VpnTransport.byId(transportType(link))
        if (!protocol.supports(transport)) throw UnsupportedOption("Транспорт")
        for (key in listOf("type", "transport", "net")) {
            val declared = link.query[key] ?: continue
            if (VpnTransport.byId(declared) != transport) throw UnsupportedOption("Противоречивый транспорт")
        }
        if (!link.query["plugin"].isNullOrEmpty()) throw UnsupportedOption("Shadowsocks plugin")
        if (listOf("pcs", "vcn", "pinsha256", "ech", "echconfig", "pqv", "mldsa65verify").any { !link.query[it].isNullOrBlank() }) {
            throw UnsupportedOption("Дополнительные параметры TLS")
        }
        val header = link.query["headertype"]
        if (!header.isNullOrBlank() && header != "none") throw UnsupportedOption("Маскировка транспорта")
        return when (scheme) {
            "vless" -> vless(link)
            "vmess" -> vmess(link)
            "trojan" -> trojan(link)
            "hysteria2", "hy2" -> hysteria2(link)
            "anytls" -> anytls(link)
            "ss" -> shadowsocks(link)
            "socks", "socks5" -> proxy(link, socks = true)
            "http", "https" -> proxy(link, socks = false)
            "tuic" -> tuic(link)
            else -> null
        }
    }

    /**
     * Короткая сводка транспорта для журнала ядра: что именно ушло в конфиг.
     *
     * Без хостов, путей и ключей — только схема (тип, режим, метод отправки,
     * размещение session/seq/payload, включено ли шифрование). Нужна, чтобы отказ
     * узла разбирался по журналу, а не догадками: «405 Method Not Allowed» от CDN
     * означает ровно одно — какой метод отправки был в конфиге.
     */
    fun describe(configJson: String): String? = describe(configJson, outboundTag = null)

    /** For grouped configs the parent supplies the actual node tag; the tag is never logged. */
    fun describe(configJson: String, outboundTag: String?): String? = runCatching {
        val config = JSONObject(configJson)
        val nodes = listOf("outbounds", "endpoints").flatMap { key ->
            val entries = config.optJSONArray(key) ?: JSONArray()
            (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
        }
        val outbound = if (outboundTag == null) nodes.firstOrNull { it.optString("type") !in setOf("direct", "block") }
            else nodes.firstOrNull { it.optString("tag") == outboundTag }
        if (outbound == null) return@runCatching null
        if (outbound.optString("type") == "wireguard") {
            val peers = outbound.optJSONArray("peers") ?: JSONArray()
            val preshared = (0 until peers.length()).any { !peers.optJSONObject(it)?.optString("pre_shared_key").isNullOrBlank() }
            return@runCatching "WireGuard endpoint · peers " + peers.length() +
                " · приватный ключ " + (if (outbound.optString("private_key").isNotBlank()) "есть" else "нет") +
                " · preshared " + (if (preshared) "есть" else "нет")
        }
        if (outbound.optString("type") in setOf("selector", "urltest")) {
            return@runCatching "группа; TLS/REALITY описываются для выбранного узла"
        }
        val parts = ArrayList<String>()
        val tls = outbound.optJSONObject("tls")
        val reality = tls?.optJSONObject("reality")
        fun presence(present: Boolean) = if (present) "есть" else "нет"
        parts.add("TLS " + presence(tls?.optBoolean("enabled") == true))
        parts.add("REALITY " + presence(reality?.optBoolean("enabled") == true))
        parts.add("flow " + when (outbound.optString("flow")) {
            "" -> "нет"
            "xtls-rprx-vision" -> "xtls-rprx-vision"
            else -> "неподдерживаемый"
        })
        parts.add("SNI " + presence(!tls?.optString("server_name").isNullOrBlank()))
        parts.add("ключ " + presence(!reality?.optString("public_key").isNullOrBlank()))
        parts.add("short-id " + presence(!reality?.optString("short_id").isNullOrBlank()))
        if (outbound.optString("encryption").isNotBlank()) parts.add("шифрование VLESS")
        outbound.optJSONObject("transport")?.let { transport ->
            if (transport.optString("type") != "xhttp") return@let
            parts.add("xhttp")
            transport.optString("mode").takeIf { it.isNotBlank() }?.let {
                parts.add("режим " + if (it in setOf("auto", "packet-up", "stream-up", "stream-one")) it else "неизвестный")
            }
            method(transport).let { parts.add("uplink " + it) }
            placement(transport, "session_placement", "session в пути").let { parts.add(it) }
            placement(transport, "seq_placement", "seq в пути").let { parts.add(it) }
            transport.optString("uplink_data_placement").takeIf { it.isNotBlank() }?.let {
                parts.add("payload " + if (it in setOf("body", "auto", "header", "cookie")) it else "неизвестный")
            }
            if (transport.optBoolean("x_padding_obfs_mode")) parts.add("padding obfs")
        }
        val type = outbound.optString("type").takeIf {
            it in setOf("vless", "vmess", "trojan", "shadowsocks", "anytls", "hysteria2", "tuic", "socks", "http", "selector", "urltest")
        } ?: "узел"
        type + " · " + parts.joinToString(", ")
    }.getOrNull()

    private fun method(transport: JSONObject): String = when (val value = transport.optString("uplink_http_method")) {
        "" -> "POST (по умолчанию)"
        "GET", "POST", "PUT", "HEAD", "OPTIONS", "PATCH", "DELETE" -> value
        else -> "неизвестный"
    }

    private fun placement(transport: JSONObject, key: String, fallback: String): String =
        transport.optString(key).takeIf { it.isNotBlank() }?.let {
            key.substringBefore('_') + " " + if (it in setOf("path", "query", "header", "cookie")) it else "неизвестное"
        }
            ?: fallback

    // --- Протоколы --------------------------------------------------------

    private fun vless(link: Link): JSONObject? {
        val uuid = decode(link.userInfo)
        if (!ProxyShareLink.isUuid(uuid)) return null
        val outbound = JSONObject()
            .put("type", "vless")
            .put("tag", TAG_PROXY)
            .put("server", link.host)
            .put("server_port", link.port)
            .put("uuid", uuid)
        // Пост-квантовое шифрование VLESS (Xray): строка
        // «mlkem768x25519plus.<native|xorpub|random>.<0rtt|1rtt>.<ключ>…» живёт внутри
        // VLESS, под транспортом и независимо от TLS. Ядро из форка его умеет
        // (SPEC 032), upstream sing-box — нет; панели включают его на узлах за CDN,
        // и без переноса строки такой узел клиента не пускает.
        link.query["encryption"]
            ?.takeIf { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }
            ?.let { outbound.put("encryption", it) }
        // XHTTP несовместим с xtls-rprx-vision: ядро форка ждёт пустой flow, а панели
        // иногда оставляют его в ссылке по инерции.
        val transport = transportType(link)
        val xtls = transport != "xhttp" && transport != "splithttp"
        if (xtls) {
            link.query["flow"]?.takeIf { it.isNotBlank() }?.let {
                if (it != "xtls-rprx-vision") throw UnsupportedOption("VLESS flow")
                outbound.put("flow", it)
            }
        }
        applyTls(link, outbound, default = false)
        applyTransport(link, outbound)
        return outbound
    }

    private fun trojan(link: Link): JSONObject? {
        val password = decode(link.userInfo)
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
        val password = decode(link.userInfo)
        if (password.isEmpty() || link.host.isEmpty()) return null
        val outbound = JSONObject()
            .put("type", "hysteria2")
            .put("tag", TAG_PROXY)
            .put("server", link.host)
            .put("server_port", link.port)
            .put("password", password)
        val obfs = link.query["obfs"]
        if (!obfs.isNullOrBlank() && obfs != "none") {
            if (obfs != "salamander") throw UnsupportedOption("Hysteria2 obfs")
            if (link.query["obfs-password"].isNullOrEmpty()) return null
            outbound.put(
                "obfs",
                JSONObject().put("type", obfs).put("password", link.query["obfs-password"] ?: ""),
            )
        }
        applyTls(link, outbound, default = true)
        return outbound
    }

    private fun anytls(link: Link): JSONObject? {
        val password = decode(link.userInfo)
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
        val method = decode(link.userInfo.substringBefore(':')).lowercase()
        val password = decode(link.userInfo.substringAfter(':', ""))
        if (method.isEmpty() || password.isEmpty()) return null
        if (method !in setOf(
                "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
                "none", "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
                "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
                "rc4-md5", "chacha20-ietf", "xchacha20",
            )) throw UnsupportedOption("Шифрование Shadowsocks")
        if ((link.query["security"] ?: link.query["tls"] ?: "none") !in setOf("none", "false", "0")) {
            throw UnsupportedOption("Shadowsocks TLS")
        }
        return JSONObject()
            .put("type", "shadowsocks")
            .put("tag", TAG_PROXY)
            .put("server", link.host)
            .put("server_port", link.port)
            .put("method", method)
            .put("password", password)
    }

    // Schema: sing-box-lx/option/{vmess,tuic,simple}.go, upstream outbound docs.
    private fun vmess(link: Link): JSONObject? {
        val uuid = decode(link.userInfo)
        if (!ProxyShareLink.isUuid(uuid)) return null
        val cipher = link.query["scy"] ?: link.query["encryption"] ?: "auto"
        if (cipher !in setOf("auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305", "aes-128-ctr")) {
            throw UnsupportedOption("Шифрование VMess")
        }
        val alterId = (link.query["aid"] ?: link.query["alterid"] ?: "0").toIntOrNull() ?: return null
        if (alterId < 0) return null
        val outbound = JSONObject().put("type", "vmess").put("tag", TAG_PROXY)
            .put("server", link.host).put("server_port", link.port)
            .put("uuid", uuid).put("security", cipher).put("alter_id", alterId)
        (link.query["packetencoding"] ?: link.query["packet_encoding"])?.let {
            if (it !in setOf("", "none", "packetaddr", "xudp")) throw UnsupportedOption("VMess UDP encoding")
            if (it != "none") outbound.put("packet_encoding", it)
        }
        applyTls(link, outbound, default = false)
        applyTransport(link, outbound)
        return outbound
    }

    private fun proxy(link: Link, socks: Boolean): JSONObject? {
        val outbound = JSONObject().put("type", if (socks) "socks" else "http").put("tag", TAG_PROXY)
            .put("server", link.host).put("server_port", link.port)
        if (socks) {
            if (link.query["version"]?.let { it != "5" } == true) throw UnsupportedOption("SOCKS version")
            outbound.put("version", "5")
            if (link.query["security"]?.let { it != "none" } == true ||
                link.query["tls"]?.let { it !in setOf("0", "false", "none") } == true) {
                throw UnsupportedOption("SOCKS TLS")
            }
        }
        if (link.userInfo.isNotEmpty()) {
            if (!link.userInfo.contains(':')) return null
            val username = decode(link.userInfo.substringBefore(':'))
            val password = decode(link.userInfo.substringAfter(':'))
            if (username.isEmpty()) return null
            if (socks && (username.toByteArray().size > 255 || password.toByteArray().size !in 1..255)) return null
            outbound.put("username", username).put("password", password)
        } else if (!socks) return null // HTTP(S) without credentials is a subscription URL.
        if (!socks) applyTls(link, outbound, default = link.scheme == "https")
        return outbound
    }

    private fun tuic(link: Link): JSONObject? {
        if (!link.userInfo.contains(':')) return null // TUIC v4 token links are incompatible.
        val uuid = decode(link.userInfo.substringBefore(':'))
        if (!ProxyShareLink.isUuid(uuid)) return null
        if (link.query["version"]?.let { it != "5" } == true) throw UnsupportedOption("TUIC v4")
        val outbound = JSONObject().put("type", "tuic").put("tag", TAG_PROXY)
            .put("server", link.host).put("server_port", link.port)
            .put("uuid", uuid).put("password", decode(link.userInfo.substringAfter(':')))
        fun option(snake: String, camel: String) = link.query[snake] ?: link.query[camel]
        option("congestion_control", "congestioncontrol")?.let {
            if (it !in setOf("cubic", "new_reno", "bbr")) throw UnsupportedOption("TUIC congestion control")
            outbound.put("congestion_control", it)
        }
        val relay = option("udp_relay_mode", "udprelaymode")
        if (relay != null) {
            if (relay !in setOf("native", "quic")) throw UnsupportedOption("TUIC UDP relay")
            outbound.put("udp_relay_mode", relay)
        }
        option("udp_over_stream", "udpoverstream")?.let {
            val enabled = boolean(it)
            if (enabled && relay != null) return null
            outbound.put("udp_over_stream", enabled)
        }
        option("zero_rtt_handshake", "zerortthandshake")?.let { outbound.put("zero_rtt_handshake", boolean(it)) }
        link.query["heartbeat"]?.let {
            if (!Regex("[0-9]+(?:\\.[0-9]+)?(?:ms|s|m|h)").matches(it)) return null
            outbound.put("heartbeat", it)
        }
        if (option("disable_sni", "disablesni")?.let(::boolean) == true) throw UnsupportedOption("TUIC disable_sni")
        applyTls(link, outbound, default = true)
        return outbound
    }

    private fun boolean(value: String): Boolean = when (value.lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> throw IllegalArgumentException()
    }

    private fun transportType(link: Link): String =
        (link.query["type"] ?: link.query["transport"] ?: link.query["net"] ?: when (link.scheme) {
            "tuic", "hysteria2", "hy2" -> "quic"
            else -> "tcp"
        }).lowercase()

    // --- Транспорт и защита ----------------------------------------------

    private fun applyTransport(link: Link, outbound: JSONObject) {
        val type = transportType(link)
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
                if (link.query["mode"]?.let { it !in setOf("gun", "none", "") } == true ||
                    !link.query["authority"].isNullOrBlank() || !link.query["host"].isNullOrBlank()) {
                    throw UnsupportedOption("Дополнительные параметры gRPC")
                }
                val transport = JSONObject().put("type", "grpc")
                val service = link.query["servicename"]
                service?.takeIf { it.isNotBlank() }?.let { transport.put("service_name", it) }
                outbound.put("transport", transport)
            }

            "xhttp", "splithttp" -> outbound.put("transport", xhttp(link))

            "http", "h2" -> {
                val transport = JSONObject().put("type", "http")
                link.query["host"]?.let { transport.put("host", JSONArray(it.split(','))) }
                link.query["path"]?.let { transport.put("path", it) }
                outbound.put("transport", transport)
            }
            "quic" -> outbound.put("transport", JSONObject().put("type", "quic"))
            "tcp", "raw" -> Unit
            else -> throw UnsupportedOption("Транспорт")
        }
    }

    /**
     * XHTTP — транспорт Xray («splithttp»), который умеет только ядро из форка
     * sing-box-lx (тег сборки `with_xhttp`, см. `.github/workflows/libbox.yml`).
     *
     * Поля приходят из двух мест: плоские параметры ссылки (`path`, `mode`, `host`, …)
     * и параметр `extra` — это URL-encoded JSON с тонкими настройками (Xray-стиль,
     * camelCase). Ключи конфига — snake_case, camelCase ядро не понимает.
     * Порядок и имена полей сверены со спецификацией форка
     * (SPECS/TASKS/002-XHTTP_CLIENT_TRANSPORT/URL_PARSING.md).
     */
    private fun xhttp(link: Link): JSONObject {
        val extra = link.query["extra"]?.let { JSONObject(it) } ?: JSONObject()
        if (extra.has("downloadSettings") || extra.has("download_settings") || link.query.containsKey("downloadsettings")) {
            throw UnsupportedOption("XHTTP downloadSettings")
        }
        val transport = JSONObject().put("type", "xhttp")

        fun text(vararg names: String): String? {
            for (name in names) {
                extra.optString(name).takeIf { it.isNotBlank() && it != "null" }?.let { return it }
                link.query[name.lowercase()]?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return null
        }

        fun flag(vararg names: String): Boolean? {
            val raw = text(*names) ?: return null
            return when (raw.trim().lowercase()) {
                "1", "true", "yes" -> true
                "0", "false", "no" -> false
                else -> throw IllegalArgumentException()
            }
        }

        // path приходит с query-хвостом («/GaMeOpTiMiZeR?ed=2048») — хвост не часть пути.
        text("path")?.substringBefore('?')?.takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
        text("host")?.let { transport.put("host", it) }
        text("mode")?.let {
            require(it.lowercase() in setOf("auto", "packet-up", "stream-up", "stream-one"))
            transport.put("mode", it.lowercase())
        }
        text("xPaddingBytes", "x_padding_bytes")?.let { transport.put("x_padding_bytes", it) }
        flag("noGRPCHeader", "no_grpc_header")?.let { transport.put("no_grpc_header", it) }

        // Вторая волна параметров XHTTP (placement, ключи, обфускация padding).
        // Панели пишут имена Xray (sessionIDPlacement, sessionIDKey, sessionIDLength),
        // спецификация ядра — sessionPlacement/sessionKey; понимаем оба написания,
        // иначе узел за CDN остаётся без размещения session и seq.
        val mapped = mapOf(
            "session_placement" to arrayOf("sessionPlacement", "sessionIDPlacement"),
            "session_key" to arrayOf("sessionKey", "sessionIDKey"),
            "session_length" to arrayOf("sessionLength", "sessionIDLength"),
            "session_table" to arrayOf("sessionTable", "sessionIDTable"),
            "seq_placement" to arrayOf("seqPlacement"),
            "seq_key" to arrayOf("seqKey"),
            "uplink_data_placement" to arrayOf("uplinkDataPlacement"),
            "uplink_data_key" to arrayOf("uplinkDataKey"),
            "uplink_http_method" to arrayOf("uplinkHTTPMethod"),
            "x_padding_key" to arrayOf("xPaddingKey"),
            "x_padding_header" to arrayOf("xPaddingHeader"),
            "x_padding_placement" to arrayOf("xPaddingPlacement"),
            "x_padding_method" to arrayOf("xPaddingMethod"),
        )
        for ((jsonKey, urlKeys) in mapped) {
            text(*urlKeys)?.let { transport.put(jsonKey, it) }
        }
        // Xray 26.7.28 uses UUIDs when the session alphabet or length is absent.
        // The embedded fork rejects a half-pair, so keep its same UUID default.
        if (transport.optString("session_table").isBlank() || transport.optString("session_length").isBlank()) {
            transport.remove("session_table")
            transport.remove("session_length")
        }
        // Флаг ставится отдельно: ядро ждёт bool, а строка «true» ломает конфиг.
        flag("xPaddingObfsMode", "x_padding_obfs_mode")?.let { transport.put("x_padding_obfs_mode", it) }

        // Диапазоны вида «3000-4000» или одиночное число; в extra числа приходят как 30.0.
        text("uplinkChunkSize", "uplink_chunk_size")?.let { transport.put("uplink_chunk_size", range(it)) }
        text("scMaxEachPostBytes", "sc_max_each_post_bytes")?.let {
            transport.put("sc_max_each_post_bytes", range(it))
        }
        text("scMinPostsIntervalMs", "sc_min_posts_interval_ms")?.let {
            transport.put("sc_min_posts_interval_ms", range(it))
        }

        extra.optJSONObject("headers")?.let { transport.put("headers", it) }
        return transport
    }

    /** «30.0» → «30», «3000-4000» → без изменений: ядро ждёт диапазон строкой. */
    private fun range(raw: String): String = raw.split('-').joinToString("-") { part ->
        part.trim().substringBefore('.').takeIf { it.isNotBlank() } ?: part.trim()
    }

    private fun applyTls(link: Link, outbound: JSONObject, default: Boolean) {
        val security = (link.query["security"] ?: link.query["tls"] ?: "").lowercase()
        val publicKey = link.query["pbk"]?.takeIf { it.isNotBlank() }
        val isReality = security == "reality" || publicKey != null
        val enabled = when (security) {
            "tls", "reality", "1", "true" -> true
            "none", "0", "false" -> false
            "" -> default || isReality
            else -> throw UnsupportedOption("Защита транспорта")
        }
        if (!enabled) {
            require(!isReality && link.scheme !in setOf("tuic", "hysteria2", "hy2", "anytls", "https"))
            require(outbound.optString("flow").isEmpty())
            return
        }
        if (isReality) {
            require(link.scheme in setOf("vless", "vmess", "trojan"))
            require(publicKey != null && Regex("[A-Za-z0-9_-]{43}").matches(publicKey))
            val sid = link.query["sid"] ?: ""
            require(sid.length <= 16 && sid.length % 2 == 0 && sid.all { it in "0123456789abcdefABCDEF" })
        }

        val tls = JSONObject().put("enabled", true)
        val serverName = link.query["sni"] ?: link.query["peer"] ?: link.query["host"]
        if (!serverName.isNullOrBlank()) tls.put("server_name", serverName)
        (link.query["allowinsecure"] ?: link.query["allow_insecure"] ?: link.query["insecure"] ?: link.query["skip-cert-verify"])
            ?.let { tls.put("insecure", boolean(it)) }
        val alpn = link.query["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (!alpn.isNullOrEmpty()) tls.put("alpn", JSONArray(alpn))
        // Отпечаток uTLS. Для REALITY это не косметика: с 08.09.2026 серверная часть
        // REALITY (xtls/reality, коммит 8cdf7bf9; Xray v26.9.8+) отбрасывает ClientHello,
        // в котором нет гибридной доли ключа X25519MLKEM768 перед обычной X25519, и уводит
        // такого клиента на настоящий сайт-заглушку. Ядро видит это как
        // «reality verification failed» и не пропускает вообще ничего.
        // В uTLS 1.8.7 (ядро sing-box 1.14.1) такую долю несёт только chrome
        // (HelloChrome_133: GREASE, X25519MLKEM768, X25519), поэтому для REALITY
        // отпечаток из ссылки не берём, а всегда ставим chrome.
        val fingerprint = if (isReality) {
            REALITY_FINGERPRINT
        } else {
            link.query["fp"]?.takeIf { it.isNotBlank() }
        }
        if (fingerprint != null && link.scheme !in setOf("tuic", "hysteria2", "hy2")) {
            if (fingerprint !in setOf("chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized")) {
                throw UnsupportedOption("TLS fingerprint")
            }
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
        }
        if (isReality) {
            val reality = JSONObject().put("enabled", true)
            if (!publicKey.isNullOrBlank()) reality.put("public_key", publicKey)
            link.query["sid"]?.takeIf { it.isNotBlank() }?.let { reality.put("short_id", it) }
            tls.put("reality", reality)
        }
        outbound.put("tls", tls)
    }

    // --- Каркас конфига ---------------------------------------------------

    /**
     * Сетевой стек TUN.
     *
     * По умолчанию gVisor: в `mixed` и `system` TCP обрабатывает системный стек
     * (NAT + возврат пакета в TUN), и на устройстве владельца TCP-соединения до
     * обработчика не доходили вообще — в журнале были только `pre-match[0] => sniff`
     * от SYN и ни одной строки `inbound connection from`, при этом UDP (DNS, QUIC)
     * работал. Xray-клиенты (INCY, Happ, v2rayNG) на этих же узлах ходят через gVisor.
     */
    private fun stackOf(variant: CoreVariant): String = when (variant) {
        CoreVariant.MIXED -> "mixed"
        CoreVariant.SYSTEM_STACK -> "system"
        else -> "gvisor"
    }

    private fun assemble(
        outbound: JSONObject,
        variant: CoreVariant,
        directMode: Boolean,
        apiSecret: String?,
        endpoint: Boolean = false,
    ): JSONObject {
        // Вариант «DNS напрямую» оставляет резолвер в сети оператора: если с ним
        // страницы открываются, значит трафик до узла не доходит из-за DNS-петли.
        // «Прямой режим» пускает мимо узла весь трафик туннеля, а не только DNS.
        val directDns = variant == CoreVariant.DIRECT_RESOLVER
        val directTraffic = directMode
        val dns = JSONObject()
            .put(
                "servers",
                JSONArray()
                    .put(JSONObject().put("type", "udp").put("tag", "dns-direct").put("server", "8.8.8.8"))
                    .put(
                        JSONObject()
                            // HTTP CONNECT cannot relay UDP DNS; use DNS-over-TCP through it.
                            .put("type", if (outbound.optString("type") == "http") "tcp" else "udp")
                            .put("tag", "dns-proxy")
                            .put("server", "1.1.1.1")
                            .put("detour", if (directDns || directTraffic) TAG_DIRECT else TAG_PROXY),
                    ),
            )
            .put("final", "dns-proxy")
            .put("strategy", "prefer_ipv4")

        val inbound = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("address", JSONArray().put(TUN_IPV4).put(TUN_IPV6))
            // 1500 — MTU мобильной сети. 9000 (значение по умолчанию в sing-box) на
            // реальном канале приводит к тому, что большие пакеты молча теряются:
            // мелкий DNS проходит, а TCP-сессии висят. Это проверено на устройстве.
            .put("mtu", TUN_MTU)
            .put("auto_route", true)
            .put("strict_route", false)
            .put("stack", stackOf(variant))

        val route = JSONObject()
            .put(
                "rules",
                JSONArray()
                    .put(JSONObject().put("action", "sniff"))
                    .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                    // QUIC в туннеле не поддержан: приложения должны уйти на TCP сразу,
                    // а не висеть на UDP/443 (так же поступает рабочий клиент на этих узлах).
                    .put(
                        JSONObject()
                            .put("network", "udp")
                            .put("port", 443)
                            .put("action", "reject"),
                    ),
            )
            .put("final", if (directTraffic) TAG_DIRECT else TAG_PROXY)
            .put("auto_detect_interface", true)
            .put("default_domain_resolver", JSONObject().put("server", "dns-direct"))

        val inbounds = JSONArray().put(inbound)
        if (variant == CoreVariant.LOCAL_PROXY) {
            // Диагностика без TUN и без маршрутов: локальный SOCKS/HTTP на телефоне.
            // Если через него страницы открываются — ядро, узел и ключ рабочие,
            // и причина в платформенном слое (TUN, маршруты, разрешения).
            inbounds
                .put(
                    JSONObject()
                        .put("type", "socks")
                        .put("tag", TAG_SOCKS)
                        .put("listen", "127.0.0.1")
                        .put("listen_port", LOCAL_PROXY_PORT)
                        .put("sniff", true),
                )
        }

        val config = JSONObject()
            // debug: пока туннель не возит трафик, сообщения ядра — единственная
            // диагностика. В интерфейс они попадают без адресов и ключей (CoreTrace).
            .put("log", JSONObject().put("level", "debug"))
            .put("dns", dns)
            .put("inbounds", inbounds)
            .put(
                "outbounds",
                JSONArray()
                    .also { if (!endpoint) it.put(outbound) }
                    .put(JSONObject().put("type", "direct").put("tag", TAG_DIRECT)),
            )
            .put("route", route)
        if (endpoint) config.put("endpoints", JSONArray().put(outbound))
        // Локальный API ядра — единственный честный источник метрик: он отдаёт
        // скорость (байт/с по туннелю) и задержку проверки узла. Слушает только
        // петлевой адрес; ключ обязателен, потому что петлевой адрес на Android
        // общий для всех приложений.
        //
        // Без ключа блок не добавляется совсем: сборка ядра без with_clash_api
        // отвергает такой конфиг целиком («clash api is not included in this build»),
        // и туннель не поднимается — метрики не стоят выключенного VPN.
        if (apiSecret != null) {
            config.put(
                "experimental",
                JSONObject().put(
                    "clash_api",
                    JSONObject()
                        .put("external_controller", LOOPBACK + ":" + CLASH_API_PORT)
                        .put("secret", apiSecret),
                ),
            )
        }
        return config
    }

    private fun decode(value: String): String = ProxyShareLink.decode(value)
}

