package com.stravo.vpn.domain.subscription

import org.json.JSONArray
import org.json.JSONObject

/** Offline WireGuard conversion. Returned links/JSON contain keys; never log them. */
object WireGuardProfile {
    /** Fixed, secret-free explanation, suitable for PanelSubscription.Conversion.Unsupported. */
    class Unsupported(val reason: String) : Exception(reason)

    fun isLink(value: String): Boolean = value.trim().substringBefore("://").lowercase() in setOf("wg", "wireguard")

    fun looksLikeConf(body: String): Boolean = body.lineSequence().any {
        it.substringBefore('#').substringBefore(';').trim().removePrefix("\uFEFF").equals("[Interface]", ignoreCase = true)
    }

    /** One link per [Interface]; all [Peer] sections stay together in the same endpoint.
     * Throws Unsupported for unsupported semantics, IllegalArgumentException for invalid data.
     * 'peers' is STRAVO's lossless extension of the INCY single-peer URI contract.
     */
    fun toLinks(body: String, name: String = "WireGuard"): List<String> {
        require(body.length <= 262144)
        val profiles = mutableListOf<Pair<MutableMap<String, String>, MutableList<MutableMap<String, String>>>>()
        var fields: MutableMap<String, String>? = null
        for (raw in body.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.substringBefore('#').substringBefore(';').trim()
            if (line.isEmpty()) continue
            if (line.startsWith('[')) {
                when (line.lowercase()) {
                    "[interface]" -> {
                        val iface = linkedMapOf<String, String>()
                        profiles.add(iface to mutableListOf())
                        fields = iface
                    }
                    "[peer]" -> {
                        require(profiles.isNotEmpty())
                        val peer = linkedMapOf<String, String>()
                        profiles.last().second.add(peer)
                        fields = peer
                    }
                    else -> throw Unsupported("Неизвестная секция WireGuard .conf")
                }
                continue
            }
            require(line.contains('='))
            val key = normalized(line.substringBefore('=').trim())
            rejectAmnezia(key)
            val target = fields ?: throw IllegalArgumentException()
            val value = line.substringAfter('=').trim()
            require(value.isNotEmpty())
            if (target.containsKey(key)) {
                if (key !in setOf("address", "allowedips", "dns")) throw IllegalArgumentException()
                target[key] = target.getValue(key) + "," + value
            } else target[key] = value
        }
        require(profiles.isNotEmpty())
        return profiles.mapIndexed { index, (iface, peers) ->
            checkFields(iface, setOf("privatekey", "address", "mtu", "listenport", "dns", "reserved", "table", "saveconfig"))
            if (iface["table"]?.let { it != "auto" } == true || iface["saveconfig"]?.lowercase()?.let { it != "false" } == true) {
                throw Unsupported("WireGuard Table/SaveConfig нельзя применить в Android VPN")
            }
            require(peers.isNotEmpty())
            val jsonPeers = JSONArray()
            for (peer in peers) {
                checkFields(peer, setOf("publickey", "presharedkey", "endpoint", "allowedips", "persistentkeepalive", "reserved"))
                val endpoint = peer["endpoint"] ?: throw IllegalArgumentException()
                require(endpoint.none { it in "/?#@\\" })
                val authority = ProxyShareLink.parse("wg://placeholder@$endpoint") ?: throw IllegalArgumentException()
                require(peer.containsKey("allowedips"))
                val effective = peer.toMutableMap()
                iface["reserved"]?.let { reserved ->
                    require(!effective.containsKey("reserved") || effective["reserved"] == reserved)
                    effective["reserved"] = reserved
                }
                jsonPeers.put(peerJson(effective, authority.host, authority.port))
            }
            val first = jsonPeers.getJSONObject(0)
            val params = linkedMapOf(
                "publickey" to first.getString("public_key"),
                "address" to prefixes(iface["address"], required = true).joinToString(","),
                "mtu" to integer(iface["mtu"] ?: "1500", 576..65535).toString(),
            )
            iface["listenport"]?.let { params["listenport"] = integer(it, 0..65535).toString() }
            iface["dns"]?.let { params["dns"] = dnsAddresses(it).joinToString(",") }
            if (peers.size == 1) {
                params["allowedips"] = strings(first.getJSONArray("allowed_ips")).joinToString(",")
                first.optString("pre_shared_key").takeIf { it.isNotEmpty() }?.let { params["presharedkey"] = it }
                params["keepalive"] = first.getInt("persistent_keepalive_interval").toString()
                first.optJSONArray("reserved")?.let { bytes -> params["reserved"] = (0 until bytes.length()).joinToString(",") { bytes.getInt(it).toString() } }
            } else params["peers"] = jsonPeers.toString()
            val host = first.getString("address")
            val authority = (if (':' in host) "[$host]" else host) + ":" + first.getInt("port")
            val label = if (profiles.size == 1) name else "$name ${index + 1}"
            val result = "wireguard://" + encode(key(iface["privatekey"])) + "@" + authority + "?" +
                params.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) } + "#" + encode(label)
            require(result.length <= 8192)
            endpoint(result, "proxy") // Offline validation of the normalized format, no network/core call.
            result
        }
    }

    /** DNS remains outside the endpoint schema; the parent can apply it to a grouped config. */
    fun dnsFor(link: String): List<String> {
        val parsed = ProxyShareLink.parse(link) ?: throw IllegalArgumentException()
        require(isLink(link))
        return parsed.query["dns"]?.let(::dnsAddresses).orEmpty()
    }

    internal fun endpoint(link: String, tag: String): JSONObject {
        require(tag.isNotBlank() && isLink(link))
        val parsed = ProxyShareLink.parse(link) ?: throw IllegalArgumentException()
        val params = linkedMapOf<String, String>()
        for ((rawKey, value) in parsed.query) {
            val name = normalized(rawKey)
            rejectAmnezia(name)
            require(!params.containsKey(name) || params[name] == value)
            params[name] = value
        }
        checkFields(params, setOf("publickey", "address", "mtu", "reserved", "allowinsecure", "presharedkey", "psk", "allowedips", "keepalive", "persistentkeepalive", "persistentkeepaliveinterval", "listenport", "peers", "dns"))
        val peers = if (params.containsKey("peers")) {
            val input = JSONArray(params.getValue("peers"))
            require(input.length() in 1..64)
            val output = JSONArray()
            for (index in 0 until input.length()) {
                val peer = input.getJSONObject(index)
                val allowedKeys = setOf("address", "port", "public_key", "pre_shared_key", "allowed_ips", "persistent_keepalive_interval", "reserved")
                if (peer.keys().asSequence().any { it !in allowedKeys }) throw Unsupported("Дополнительные параметры WireGuard peer не поддерживаются")
                val host = peer.getString("address")
                val port = integer(peer.get("port").toString(), 1..65535)
                val authority = ProxyShareLink.parse("wg://placeholder@${if (':' in host) "[$host]" else host}:$port") ?: throw IllegalArgumentException()
                val values = linkedMapOf(
                    "publickey" to peer.getString("public_key"),
                    "allowedips" to strings(peer.getJSONArray("allowed_ips")).joinToString(","),
                    "persistentkeepalive" to peer.opt("persistent_keepalive_interval")?.toString().orEmpty().ifEmpty { "0" },
                )
                if (peer.has("pre_shared_key")) values["presharedkey"] = peer.getString("pre_shared_key")
                if (peer.has("reserved")) {
                    val bytes = peer.getJSONArray("reserved")
                    values["reserved"] = (0 until bytes.length()).joinToString(",") { bytes.get(it).toString() }
                }
                output.put(peerJson(values, authority.host, authority.port))
            }
            val first = output.getJSONObject(0)
            require(first.getString("address") == parsed.host && first.getInt("port") == parsed.port)
            require(first.getString("public_key") == key(params["publickey"]))
            require(listOf("reserved", "presharedkey", "psk", "allowedips", "keepalive", "persistentkeepalive", "persistentkeepaliveinterval").none { params.containsKey(it) })
            output
        } else JSONArray().put(peerJson(params, parsed.host, parsed.port))
        val addresses = prefixes(params["address"], required = true)
        val mtu = integer(params["mtu"] ?: "1500", 576..65535)
        require(mtu >= 1280 || addresses.none { ':' in it })
        val endpoint = JSONObject().put("type", "wireguard").put("tag", tag).put("system", false)
            .put("address", JSONArray(addresses))
            .put("private_key", key(ProxyShareLink.decode(parsed.userInfo)))
            .put("mtu", mtu).put("peers", peers)
        params["listenport"]?.let { endpoint.put("listen_port", integer(it, 0..65535)) }
        params["dns"]?.let(::dnsAddresses)
        return endpoint
    }

    private fun peerJson(values: Map<String, String>, host: String, port: Int): JSONObject {
        val peer = JSONObject().put("address", host).put("port", port)
            .put("public_key", key(values["publickey"]))
            .put("allowed_ips", JSONArray(prefixes(values["allowedips"] ?: "0.0.0.0/0,::/0", required = true)))
            .put("persistent_keepalive_interval", integer(alias(values, "keepalive", "persistentkeepalive", "persistentkeepaliveinterval") ?: "0", 0..65535))
        alias(values, "presharedkey", "psk")?.let { peer.put("pre_shared_key", key(it)) }
        values["reserved"]?.let { raw ->
            val bytes = raw.split(',').map { integer(it.trim(), 0..255) }
            require(bytes.size == 3)
            peer.put("reserved", JSONArray(bytes))
        }
        return peer
    }

    private fun alias(values: Map<String, String>, vararg names: String): String? {
        val supplied = names.mapNotNull { values[it] }.distinct()
        require(supplied.size <= 1)
        return supplied.firstOrNull()
    }

    private fun key(value: String?): String {
        require(value != null)
        val normalized = value.replace('-', '+').replace('_', '/').removeSuffix("=")
        require(Regex("[A-Za-z0-9+/]{43}").matches(normalized) && normalized.last() in "AEIMQUYcgkosw048")
        return "$normalized="
    }

    private fun prefixes(raw: String?, required: Boolean): List<String> {
        if (raw.isNullOrBlank()) {
            if (required) throw Unsupported("WireGuard: требуется address/AllowedIPs; адрес туннеля нельзя угадать")
            return emptyList()
        }
        return raw.split(',').map { part ->
            val value = part.trim()
            val address = value.substringBefore('/')
            require(ip(address))
            val bits = if (':' in address) 128 else 32
            val prefix = if ('/' in value) integer(value.substringAfter('/'), 0..bits) else bits
            "$address/$prefix"
        }.distinct()
    }

    private fun dnsAddresses(raw: String): List<String> = raw.split(',').map {
        val value = it.trim()
        if (!ip(value)) throw Unsupported("WireGuard DNS: поддерживаются только IP-адреса, без search-domain")
        value
    }.distinct()

    // Numeric-only validation: no InetAddress/DNS resolution during import.
    private fun ip(value: String): Boolean {
        fun ipv4(text: String): Boolean {
            val octets = text.split('.')
            return octets.size == 4 && octets.all {
                it.isNotEmpty() && it.length <= 3 && (it.length == 1 || it.first() != '0') &&
                    it.all { char -> char in '0'..'9' } && it.toIntOrNull()?.let { number -> number in 0..255 } == true
            }
        }
        if (':' !in value) return ipv4(value)
        if (value.any { it !in "0123456789abcdefABCDEF:." } || ":::" in value) return false
        val halves = value.split("::")
        if (halves.size > 2) return false
        val groups = halves.flatMap { if (it.isEmpty()) emptyList() else it.split(':') }
        var count = 0
        for ((index, group) in groups.withIndex()) {
            if ('.' in group) {
                if (index != groups.lastIndex || !ipv4(group)) return false
                count += 2
            } else {
                if (!Regex("[0-9a-fA-F]{1,4}").matches(group)) return false
                count++
            }
        }
        return if (halves.size == 2) count < 8 else count == 8
    }

    private fun integer(value: String, range: IntRange): Int {
        require(value.isNotEmpty() && value.all { it in '0'..'9' })
        return (value.toIntOrNull() ?: throw IllegalArgumentException()).also { require(it in range) }
    }

    private fun checkFields(values: Map<String, String>, allowed: Set<String>) {
        for (name in values.keys) {
            rejectAmnezia(name)
            if (name !in allowed) throw Unsupported("Дополнительные параметры или команды WireGuard .conf не поддерживаются")
        }
    }

    private fun rejectAmnezia(name: String) {
        if (name.startsWith("awg") || name.startsWith("amnezia") || Regex("jc|jmin|jmax|[sh][1-4]|i[1-5]").matches(name)) {
            throw Unsupported("AmneziaWG не поддерживается: обфускация не будет отброшена")
        }
    }

    private fun normalized(value: String): String = value.lowercase().replace("_", "").replace("-", "")
    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }
    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
