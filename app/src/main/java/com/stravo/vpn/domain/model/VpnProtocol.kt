package com.stravo.vpn.domain.model

/**
 * Транспорт, поверх которого работает протокол. XHTTP — отдельный транспорт VLESS,
 * он едет по обычному HTTP/1.1- или HTTP/2-соединению и не требует WebSocket-апгрейда.
 */
enum class VpnTransport(val id: String, val displayName: String) {
    TCP("tcp", "TCP"),
    WEBSOCKET("ws", "WebSocket"),
    HTTP_UPGRADE("httpupgrade", "HTTP Upgrade"),
    HTTP("http", "HTTP/2"),
    XHTTP("xhttp", "XHTTP"),
    GRPC("grpc", "gRPC"),
    QUIC("quic", "QUIC"),
    UDP("udp", "UDP"),
    UNKNOWN("unknown", "—"),
    ;

    companion object {
        fun byId(id: String?): VpnTransport {
            if (id == null) return UNKNOWN
            val normalized = id.trim().lowercase()
            // В разных ядрах один и тот же транспорт называется по-разному.
            val alias = when (normalized) {
                "raw" -> "tcp"
                "splithttp" -> "xhttp"
                "websocket" -> "ws"
                "h2" -> "http"
                "hysteria", "hysteria2", "hy2" -> "quic"
                else -> normalized
            }
            return entries.firstOrNull { it.id == alias } ?: UNKNOWN
        }
    }
}

/** Тип защиты транспорта. Reality и TLS влияют на то, как ядро строит рукопожатие. */
enum class VpnSecurity(val id: String, val displayName: String) {
    NONE("none", "—"),
    TLS("tls", "TLS"),
    REALITY("reality", "Reality"),
    ;

    companion object {
        fun byId(id: String?): VpnSecurity = when (id?.trim()?.lowercase()) {
            "tls" -> TLS
            "reality" -> REALITY
            else -> NONE
        }
    }
}

/** Описание протокола: схемы ссылок и допустимые транспорты. */
data class VpnProtocol(
    val id: String,
    val displayName: String,
    val schemes: List<String>,
    val defaultTransport: VpnTransport,
    val transports: List<VpnTransport>,
    val defaultSecurity: VpnSecurity = VpnSecurity.NONE,
) {
    fun supports(transport: VpnTransport): Boolean = transports.contains(transport)
}

/**
 * Единственный источник правды о поддерживаемых протоколах.
 * Порядок в [settingsLabels] совпадает с порядком в настройках приложения.
 */
object ProtocolCatalog {

    val VLESS = VpnProtocol(
        id = "vless",
        displayName = "VLESS",
        schemes = listOf("vless"),
        defaultTransport = VpnTransport.TCP,
        transports = listOf(
            VpnTransport.TCP,
            VpnTransport.WEBSOCKET,
            VpnTransport.HTTP_UPGRADE,
            VpnTransport.HTTP,
            VpnTransport.XHTTP,
            VpnTransport.GRPC,
            VpnTransport.QUIC,
        ),
    )

    val VMESS = VpnProtocol(
        id = "vmess",
        displayName = "VMess",
        schemes = listOf("vmess"),
        defaultTransport = VpnTransport.TCP,
        transports = VLESS.transports,
    )

    val SOCKS5 = VpnProtocol(
        id = "socks5",
        displayName = "SOCKS5",
        schemes = listOf("socks", "socks5"),
        defaultTransport = VpnTransport.TCP,
        transports = listOf(VpnTransport.TCP),
    )

    val HTTP = VpnProtocol(
        id = "http",
        displayName = "HTTP(S) proxy",
        schemes = listOf("http", "https"),
        defaultTransport = VpnTransport.TCP,
        transports = listOf(VpnTransport.TCP),
    )

    val TUIC = VpnProtocol(
        id = "tuic",
        displayName = "TUIC v5",
        schemes = listOf("tuic"),
        defaultTransport = VpnTransport.QUIC,
        transports = listOf(VpnTransport.QUIC),
        defaultSecurity = VpnSecurity.TLS,
    )

    val WIREGUARD = VpnProtocol(
        id = "wireguard",
        displayName = "WireGuard",
        schemes = listOf("wireguard", "wg"),
        defaultTransport = VpnTransport.UDP,
        transports = listOf(VpnTransport.UDP),
    )

    val AMNEZIAWG = VpnProtocol(
        id = "amneziawg",
        displayName = "AmneziaWG",
        schemes = listOf("amneziawg", "awg"),
        defaultTransport = VpnTransport.UDP,
        transports = listOf(VpnTransport.UDP),
    )

    val ANYTLS = VpnProtocol(
        id = "anytls",
        displayName = "AnyTLS",
        schemes = listOf("anytls"),
        defaultTransport = VpnTransport.TCP,
        transports = listOf(VpnTransport.TCP),
        defaultSecurity = VpnSecurity.TLS,
    )

    val HYSTERIA2 = VpnProtocol(
        id = "hysteria2",
        displayName = "Hysteria2",
        schemes = listOf("hysteria2", "hy2"),
        defaultTransport = VpnTransport.QUIC,
        transports = listOf(VpnTransport.QUIC),
        defaultSecurity = VpnSecurity.TLS,
    )

    val TROJAN = VpnProtocol(
        id = "trojan",
        displayName = "Trojan",
        schemes = listOf("trojan"),
        defaultTransport = VpnTransport.TCP,
        transports = VLESS.transports,
        defaultSecurity = VpnSecurity.TLS,
    )

    val SHADOWSOCKS = VpnProtocol(
        id = "shadowsocks",
        displayName = "Shadowsocks",
        schemes = listOf("ss"),
        defaultTransport = VpnTransport.TCP,
        transports = listOf(VpnTransport.TCP),
    )

    /** Legacy-схема: распознаётся для честного Unsupported; рабочий транспорт не реализован. */
    val WEBRTC = VpnProtocol(
        id = "webrtc",
        displayName = "WebRTC",
        schemes = listOf("webrtc", "stravo"),
        defaultTransport = VpnTransport.QUIC,
        transports = listOf(VpnTransport.QUIC),
        defaultSecurity = VpnSecurity.TLS,
    )

    // WireGuard and AmneziaWG use endpoints. Legacy WebRTC is recognized but not implemented.
    private val supported: List<VpnProtocol> = listOf(VLESS, ANYTLS, HYSTERIA2, TROJAN, SHADOWSOCKS, VMESS, SOCKS5, HTTP, TUIC, WIREGUARD, AMNEZIAWG)
    val all: List<VpnProtocol> = supported + WEBRTC

    /** Схемы, которые приложение вообще умеет читать из подписки. */
    val knownSchemes: List<String> = all.flatMap { it.schemes }

    fun byScheme(scheme: String?): VpnProtocol? {
        if (scheme == null) return null
        val normalized = scheme.trim().lowercase()
        return all.firstOrNull { protocol -> protocol.schemes.any { it == normalized } }
    }

    fun byId(id: String?): VpnProtocol? {
        if (id == null) return null
        return all.firstOrNull { it.id == id.trim().lowercase() }
    }

    /** Подписи для экрана настроек: «Авто», протоколы и отдельная строка для VLESS + XHTTP. */
    val settingsLabels: List<String> = buildList {
        add("Авто (рекомендуется)")
        addAll(supported.map { it.displayName })
        add(VLESS.displayName + " + " + VpnTransport.XHTTP.displayName)
    }

    /**
     * Короткие подписи для полосы протоколов на TV: там мало места,
     * длинные строки вроде «VLESS + XHTTP» не помещаются в чип.
     */
    val stripLabels: List<String> = listOf("Авто") + supported.map { it.displayName } + VpnTransport.XHTTP.displayName
}

