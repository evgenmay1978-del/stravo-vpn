package com.stravo.vpn.engine.box

import com.stravo.vpn.domain.subscription.Base64Codec
import com.stravo.vpn.domain.subscription.SubscriptionLinkParser
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
 * Hysteria2, Trojan и Shadowsocks — то, что закрывает libbox. Если транспорта нет
 * в сборке ядра, возвращается честный [CoreConfig.Unsupported], а не тихий отказ.
 *
 * Конфиг никогда не логируется и не попадает в UI-состояние.
 */
object SingBoxConfigBuilder {

    private const val TAG_PROXY = "proxy"
    private const val TAG_DIRECT = "direct"
    private const val TAG_BLOCK = "block-quic"
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

    /** Транспорты, которых нет в этой сборке ядра (docs/IMPLEMENTATION.md, раздел 1c). */
    /**
     * Транспорты, которых нет даже в сборке ядра из форка. XHTTP здесь больше нет:
     * его закрывает sing-box-lx с тегом `with_xhttp` (см. `libbox.yml`).
     */
    private val unsupportedTransports = emptySet<String>()

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

        return CoreConfig.Ready(assemble(outbound, variant, directMode, apiSecret).toString())
    }

    /**
     * Короткая сводка транспорта для журнала ядра: что именно ушло в конфиг.
     *
     * Без хостов, путей и ключей — только схема (тип, режим, метод отправки,
     * размещение session/seq/payload, включено ли шифрование). Нужна, чтобы отказ
     * узла разбирался по журналу, а не догадками: «405 Method Not Allowed» от CDN
     * означает ровно одно — какой метод отправки был в конфиге.
     */
    fun describe(configJson: String): String? = runCatching {
        val outbound = JSONObject(configJson).optJSONArray("outbounds")?.optJSONObject(0)
            ?: return@runCatching null
        val parts = ArrayList<String>()
        if (outbound.optString("encryption").isNotBlank()) parts.add("шифрование VLESS")
        outbound.optJSONObject("transport")?.let { transport ->
            if (transport.optString("type") != "xhttp") return@let
            parts.add("xhttp")
            transport.optString("mode").takeIf { it.isNotBlank() }?.let { parts.add("режим " + it) }
            method(transport).let { parts.add("uplink " + it) }
            placement(transport, "session_placement", "session в пути").let { parts.add(it) }
            placement(transport, "seq_placement", "seq в пути").let { parts.add(it) }
            transport.optString("uplink_data_placement").takeIf { it.isNotBlank() }
                ?.let { parts.add("payload " + it) }
            if (transport.optBoolean("x_padding_obfs_mode")) parts.add("padding obfs")
        }
        outbound.optString("type") + if (parts.isEmpty()) "" else " · " + parts.joinToString(", ")
    }.getOrNull()

    private fun method(transport: JSONObject): String =
        transport.optString("uplink_http_method").takeIf { it.isNotBlank() } ?: "POST (по умолчанию)"

    private fun placement(transport: JSONObject, key: String, fallback: String): String =
        transport.optString(key).takeIf { it.isNotBlank() }?.let { key.substringBefore('_') + " " + it }
            ?: fallback

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
        val transport = (link.query["type"] ?: link.query["net"] ?: "").lowercase()
        val xtls = transport != "xhttp" && transport != "splithttp"
        if (xtls) {
            link.query["flow"]?.takeIf { it.isNotBlank() }?.let { outbound.put("flow", it) }
        }
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
        // SIP002: учётные данные могут быть как method:password, так и base64 от них.
        val credentials = if (link.userInfo.contains(':')) {
            link.userInfo
        } else {
            Base64Codec.decodeOrNull(link.userInfo)?.trim() ?: decode(link.userInfo)
        }
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

            "xhttp", "splithttp" -> outbound.put("transport", xhttp(link))

            "quic" -> outbound.put("transport", JSONObject().put("type", "quic"))
            else -> Unit
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
        val extra = runCatching { JSONObject(link.query["extra"] ?: "") }.getOrElse { JSONObject() }
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
                else -> null
            }
        }

        // path приходит с query-хвостом («/GaMeOpTiMiZeR?ed=2048») — хвост не часть пути.
        text("path")?.substringBefore('?')?.takeIf { it.isNotBlank() }?.let { transport.put("path", it) }
        text("host")?.let { transport.put("host", it) }
        text("mode")?.let { transport.put("mode", it.lowercase()) }
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
        val publicKey = link.query["pbk"]
        val isReality = security == "reality" || !publicKey.isNullOrBlank()
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
        if (fingerprint != null) {
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
                            .put("type", "udp")
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
                            .put("action", "route")
                            .put("outbound", TAG_BLOCK),
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
                    .put(outbound)
                    .put(JSONObject().put("type", "direct").put("tag", TAG_DIRECT))
                    .put(JSONObject().put("type", "block").put("tag", TAG_BLOCK)),
            )
            .put("route", route)
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

