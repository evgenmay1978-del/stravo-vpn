package com.stravo.vpn.engine.box

import com.stravo.vpn.data.settings.StravoSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * User DNS/routing overlay for a freshly built STRAVO sing-box 1.14 configuration.
 * Call once after building; never log settings or the resulting JSON.
 * Only dns/route are replaced. Outbounds, transports, TLS, TUN and mode stay intact.
 *
 * Field references (modern DNS servers, not the removed legacy address format):
 * https://sing-box.sagernet.org/configuration/dns/server/https/
 * https://sing-box.sagernet.org/configuration/dns/server/udp/
 * https://sing-box.sagernet.org/configuration/shared/dial/
 * https://sing-box.sagernet.org/configuration/route/rule_action/
 * https://sing-box.sagernet.org/configuration/dns/rule_action/
 */
object NetworkPolicy {
    const val MIN_PROBE_TIMEOUT_MS = 2_000
    const val MAX_PROBE_TIMEOUT_MS = 30_000
    const val MAX_RULES_PER_LIST = 500
    val ipv6Policies: List<String> = listOf("prefer_ipv4", "ipv4_only", "prefer_ipv6")

    private const val DIRECT_DNS = "dns-direct"
    private const val REMOTE_DNS = "dns-proxy"
    private const val BOOTSTRAP_DNS = "stravo-dns-bootstrap"

    /** Pure syntax checks: no DNS resolution, network access, or URL-bearing errors. */
    fun validate(settings: StravoSettings): String? {
        if (!validDns(settings.directDns)) {
            return "Прямой DNS: укажите HTTPS-адрес DoH или IP-адрес DNS без порта."
        }
        if (!validDns(settings.remoteDns)) {
            return "DNS через VPN: укажите HTTPS-адрес DoH или IP-адрес DNS без порта."
        }
        if (settings.ipv6Policy !in ipv6Policies) return "Выберите допустимый режим IPv6."
        if (httpsUri(settings.probeUrl) == null) {
            return "Адрес проверки: нужен HTTPS-адрес без логина, пароля, параметров и фрагмента."
        }
        if (settings.probeTimeoutMs !in MIN_PROBE_TIMEOUT_MS..MAX_PROBE_TIMEOUT_MS) {
            return "Время ожидания проверки должно быть от 2 до 30 секунд."
        }
        val domains = listOf(settings.directDomains, settings.proxyDomains, settings.blockDomains)
        val ips = listOf(settings.directIpCidrs, settings.proxyIpCidrs, settings.blockIpCidrs)
        val names = listOf("Напрямую", "Через VPN", "Блокировать")
        for ((index, entries) in domains.withIndex()) {
            if (entries.size > MAX_RULES_PER_LIST) return "Домены «${names[index]}»: не более 500 записей."
            if (entries.any { domain(it) == null }) {
                return "Домены «${names[index]}»: нужен домен или *.домен, без URL и порта."
            }
        }
        for ((index, entries) in ips.withIndex()) {
            if (entries.size > MAX_RULES_PER_LIST) return "IP «${names[index]}»: не более 500 записей."
            if (entries.any { cidr(it) == null }) {
                return "IP «${names[index]}»: нужен IPv4, IPv6 или CIDR с допустимой маской."
            }
        }
        if (duplicatesAcross(domains.map { entries -> entries.mapNotNull(::domain).toSet() })) {
            return "Один домен указан в разных списках. Оставьте для него одно действие."
        }
        if (duplicatesAcross(ips.map { entries -> entries.mapNotNull(::cidr).toSet() })) {
            return "Один IP или CIDR указан в разных списках. Оставьте для него одно действие."
        }
        return null
    }

    /** New lines, commas and semicolons separate entries; spaces inside an entry are invalid. */
    fun parseList(text: String): Set<String> = text.split('\n', '\r', ',', ';')
        .map(String::trim).filter(String::isNotEmpty).toSet()

    /** The caller validates before saving and also before passing persisted values to the core. */
    fun apply(config: JSONObject, settings: StravoSettings) {
        val error = validate(settings)
        require(error == null) { error ?: "Некорректные настройки сети." }
        val originalDns = config.optJSONObject("dns")
        val originalRoute = config.optJSONObject("route")
        require(originalDns != null && originalRoute != null) { "В конфигурации отсутствуют DNS или маршруты." }
        val dns = JSONObject(originalDns.toString())
        val route = JSONObject(originalRoute.toString())
        val servers = dns.optJSONArray("servers") ?: JSONArray()
        val originalRemote = (0 until servers.length()).mapNotNull(servers::optJSONObject)
            .firstOrNull { it.optString("tag") == REMOTE_DNS }
        // Keep DIRECT_RESOLVER and the explicit diagnostic direct mode intact.
        val directMode = route.optString("final") == "direct"
        val remoteDetour = if (directMode) "direct" else
            originalRemote?.optString("detour")?.takeIf { it.isNotBlank() } ?: "proxy"
        val replacements = linkedMapOf(
            DIRECT_DNS to dnsServer(settings.directDns, DIRECT_DNS, null, BOOTSTRAP_DNS),
            REMOTE_DNS to dnsServer(settings.remoteDns, REMOTE_DNS, remoteDetour, DIRECT_DNS),
        )
        val newServers = JSONArray()
        for (index in 0 until servers.length()) {
            val server = servers.getJSONObject(index)
            val tag = server.optString("tag")
            if (tag != BOOTSTRAP_DNS) newServers.put(replacements.remove(tag) ?: server)
        }
        replacements.values.forEach(newServers::put)
        // Resolve a hostname-based direct DoH using the platform resolver, never itself.
        // This bootstrap is used only for the resolver's hostname, not ordinary DNS queries.
        if (httpsUri(settings.directDns)?.let { ipBits(uriHost(it)) == null } == true) {
            newServers.put(JSONObject().put("type", "local").put("tag", BOOTSTRAP_DNS))
        }
        dns.put("servers", newServers).put("strategy", settings.ipv6Policy)
        if (dns.optString("final").isBlank()) dns.put("final", REMOTE_DNS)

        val dnsRules = JSONArray()
        domainRule(settings.blockDomains)?.let { dnsRules.put(it.put("action", "reject")) }
        domainRule(settings.proxyDomains)?.let {
            dnsRules.put(it.put("action", "route").put("server", REMOTE_DNS))
        }
        domainRule(settings.directDomains)?.let {
            dnsRules.put(it.put("action", "route").put("server", DIRECT_DNS))
        }
        if (dnsRules.length() > 0) {
            dns.put("rules", insertAfterGuards(dns.optJSONArray("rules"), dnsRules, emptySet()))
            dns.put("reverse_mapping", true)
        }

        // Keep the builder's resolver tag/options; strategy affects direct dialing and
        // domain-based VPN endpoint lookup without editing any outbound or TLS field.
        val resolver = when (val existing = route.opt("default_domain_resolver")) {
            is JSONObject -> JSONObject(existing.toString())
            is String -> JSONObject().put("server", existing)
            else -> JSONObject().put("server", DIRECT_DNS)
        }
        resolver.put("strategy", settings.ipv6Policy)
        route.put("default_domain_resolver", resolver)

        val rules = JSONArray()
        if (settings.ipv6Policy == "ipv4_only") {
            rules.put(JSONObject().put("ip_version", 6).put("action", "reject"))
        }
        // Resolve domain destinations when an IP rule or address-family preference
        // needs the actual address. DNS domain rules above select direct vs remote DNS.
        if (settings.bypassLan || settings.ipv6Policy != "prefer_ipv4" ||
            settings.directIpCidrs.isNotEmpty() || settings.proxyIpCidrs.isNotEmpty() ||
            settings.blockIpCidrs.isNotEmpty()
        ) {
            rules.put(JSONObject().put("action", "resolve").put("strategy", settings.ipv6Policy))
        }
        // Block > proxy > direct > LAN. Base sniff/hijack/reject/QUIC guards stay ahead.
        addRouteRules(rules, settings.blockDomains, settings.blockIpCidrs, null)
        addRouteRules(rules, settings.proxyDomains, settings.proxyIpCidrs, if (directMode) "direct" else "proxy")
        addRouteRules(rules, settings.directDomains, settings.directIpCidrs, "direct")
        if (settings.bypassLan) {
            rules.put(JSONObject().put("ip_is_private", true).put("action", "route").put("outbound", "direct"))
        }
        if (rules.length() > 0) {
            val outbounds = config.optJSONArray("outbounds") ?: JSONArray()
            val blockedTags = (0 until outbounds.length()).mapNotNull(outbounds::optJSONObject)
                .filter { it.optString("type") == "block" }.map { it.optString("tag") }.toSet()
            route.put("rules", insertAfterGuards(route.optJSONArray("rules"), rules, blockedTags))
        }
        config.put("dns", dns).put("route", route)
    }

    /** Values/addresses/IDs never leave these helpers; safe for a UI or redacted log. */
    fun changedSections(before: StravoSettings, after: StravoSettings): Set<String> = buildSet {
        if (before.directDns != after.directDns || before.remoteDns != after.remoteDns ||
            before.ipv6Policy != after.ipv6Policy || before.preferProfileDns != after.preferProfileDns
        ) add("DNS и IPv6")
        if (before.directDomains != after.directDomains || before.proxyDomains != after.proxyDomains ||
            before.blockDomains != after.blockDomains || before.directIpCidrs != after.directIpCidrs ||
            before.proxyIpCidrs != after.proxyIpCidrs || before.blockIpCidrs != after.blockIpCidrs ||
            before.bypassLan != after.bypassLan
        ) add("Маршруты")
        if (before.appMode != after.appMode || before.apps != after.apps) add("Приложения")
        if (before.coreDirectMode != after.coreDirectMode) add("Диагностика")
        if (before.autoConnect != after.autoConnect || before.startOnBoot != after.startOnBoot ||
            before.autoReconnect != after.autoReconnect || before.autoSelect != after.autoSelect ||
            before.autoFailover != after.autoFailover || before.networkCheck != after.networkCheck ||
            before.probeUrl != after.probeUrl || before.probeTimeoutMs != after.probeTimeoutMs
        ) add("Подключение")
        if (before.selectedSourceId != after.selectedSourceId || before.selectedNodeId != after.selectedNodeId ||
            before.selectedMode != after.selectedMode
        ) add("Выбор узла")
    }

    /** Source/node selection is handled by the parent separately. Does not start a stopped VPN. */
    fun requiresReconnect(before: StravoSettings, after: StravoSettings): Boolean =
        changedSections(before, after).any { it in setOf("DNS и IPv6", "Маршруты", "Приложения", "Диагностика") }

    private fun dnsServer(value: String, tag: String, detour: String?, resolver: String): JSONObject {
        val uri = httpsUri(value)
        val server = JSONObject().put("tag", tag)
        if (uri == null) {
            server.put("type", "udp").put("server", value.trim())
        } else {
            val host = uriHost(uri)
            server.put("type", "https").put("server", host)
                .put("server_port", if (uri.port == -1) 443 else uri.port)
                .put("path", uri.rawPath.takeIf { !it.isNullOrEmpty() } ?: "/dns-query")
            if (ipBits(host) == null) {
                server.put("domain_resolver", JSONObject().put("server", resolver))
            }
            // HTTPS transport enables TLS with normal certificate verification by default.
        }
        if (detour != null) server.put("detour", detour)
        return server
    }

    private fun addRouteRules(rules: JSONArray, domains: Set<String>, ips: Set<String>, outbound: String?) {
        fun add(rule: JSONObject) {
            rule.put("action", if (outbound == null) "reject" else "route")
            if (outbound != null) rule.put("outbound", outbound)
            rules.put(rule)
        }
        domainRule(domains)?.let(::add)
        if (ips.isNotEmpty()) add(JSONObject().put("ip_cidr", JSONArray(ips.mapNotNull(::cidr).sorted())))
    }

    private fun domainRule(entries: Set<String>): JSONObject? {
        if (entries.isEmpty()) return null
        val normalized = entries.mapNotNull(::domain).sorted()
        val exact = normalized.filterNot { it.startsWith("*.") }
        val suffix = normalized.filter { it.startsWith("*.") }.map { it.removePrefix("*.") }
        return JSONObject().apply {
            if (exact.isNotEmpty()) put("domain", JSONArray(exact))
            if (suffix.isNotEmpty()) put("domain_suffix", JSONArray(suffix))
        }
    }

    /** Do not bypass existing security guards or lose any original rule/order. */
    private fun insertAfterGuards(existing: JSONArray?, added: JSONArray, blockedTags: Set<String>): JSONArray {
        val old = existing ?: JSONArray()
        var lastGuard = -1
        for (index in 0 until old.length()) {
            val rule = old.optJSONObject(index) ?: continue
            if (rule.optString("action") in setOf("sniff", "hijack-dns", "reject") ||
                rule.optString("outbound") in blockedTags
            ) lastGuard = index
        }
        return JSONArray().apply {
            for (index in 0..old.length()) {
                if (index == lastGuard + 1) for (newIndex in 0 until added.length()) put(added.get(newIndex))
                if (index < old.length()) put(old.get(index))
            }
        }
    }

    private fun validDns(value: String): Boolean = ipBits(value.trim()) != null || httpsUri(value) != null

    private fun httpsUri(value: String): URI? = runCatching {
        val text = value.trim()
        if (text.length > 2048 || text.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = URI(text)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            (uri.port != -1 && uri.port !in 1..65535) || uri.rawAuthority.endsWith(":")
        ) return null
        val host = uriHost(uri)
        if (ipBits(host) == null && domain(host)?.startsWith("*.") != false) return null
        uri
    }.getOrNull()

    private fun uriHost(uri: URI): String = uri.host.removePrefix("[").removeSuffix("]")

    private fun domain(value: String): String? = runCatching {
        val text = value.trim().lowercase(Locale.ROOT).removeSuffix(".")
        val suffix = text.startsWith("*.") || text.startsWith('.')
        val name = text.removePrefix("*.").removePrefix(".")
        if (name.isEmpty() || name.length > 253 || name.all { it.isDigit() || it == '.' }) return null
        val ascii = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        if (ascii.length > 253 || ascii.split('.').any {
                it.isEmpty() || it.length > 63 || !it.first().isLetterOrDigit() ||
                    !it.last().isLetterOrDigit() || it.any { char -> char !in 'a'..'z' && char !in '0'..'9' && char != '-' }
            }) return null
        (if (suffix) "*." else "") + ascii
    }.getOrNull()

    private fun cidr(value: String): String? {
        val text = value.trim().lowercase(Locale.ROOT)
        val parts = text.split('/')
        if (parts.size !in 1..2) return null
        val bits = ipBits(parts[0]) ?: return null
        val prefix = if (parts.size == 1) bits else {
            val raw = parts[1]
            if (raw.isEmpty() || raw.any { it !in '0'..'9' }) return null
            raw.toIntOrNull()?.takeIf { it in 0..bits } ?: return null
        }
        return "${parts[0]}/$prefix"
    }

    /** Literal parser only: unlike InetAddress.getByName this cannot perform a lookup. */
    private fun ipBits(value: String): Int? {
        fun ipv4(text: String): Boolean {
            val parts = text.split('.')
            return parts.size == 4 && parts.all {
                it.isNotEmpty() && it.length <= 3 && it.all { ch -> ch in '0'..'9' } &&
                    (it.length == 1 || it[0] != '0') && (it.toIntOrNull() ?: -1) in 0..255
            }
        }
        if (':' !in value) return if (ipv4(value)) 32 else null
        if (value.length > 45 || value.any { it !in "0123456789abcdefABCDEF:." }) return null
        val halves = value.split("::")
        if (halves.size > 2) return null
        val groups = halves.flatMap { if (it.isEmpty()) emptyList() else it.split(':') }
        var count = 0
        for ((index, group) in groups.withIndex()) {
            if ('.' in group) {
                if (index != groups.lastIndex || !value.endsWith(group) || !ipv4(group)) return null
                count += 2
            } else {
                if (group.length !in 1..4 || group.any { it !in "0123456789abcdefABCDEF" }) return null
                count++
            }
        }
        return if ((halves.size == 2 && count < 8) || (halves.size == 1 && count == 8)) 128 else null
    }

    private fun duplicatesAcross(lists: List<Set<String>>): Boolean =
        lists.sumOf { it.size } != lists.flatten().toSet().size
}
