package com.stravo.vpn.domain.subscription

import java.net.URI
import java.net.URLDecoder
import org.json.JSONObject
import com.stravo.vpn.domain.model.VpnTransport
import com.stravo.vpn.domain.model.VpnSecurity

/** Service markers emitted by Maestro's whitelist_links.go; never classify by XHTTP alone. */
object SubscriptionServiceClassifier {
    fun isCdnSource(url: String?): Boolean = runCatching {
        URI(url.orEmpty()).path.orEmpty().startsWith("/cdn-sub/")
    }.getOrDefault(false)

    fun classify(link: String, sourceUrl: String? = null): SubscriptionService {
        if (isCdnSource(sourceUrl)) return SubscriptionService.CDN
        val parsed = SubscriptionLinkParser.parse(link) as? ParsedLink.Node
            ?: return SubscriptionService.UNKNOWN
        val params = link.substringBefore('#').substringAfter('?', "").split('&')
            .associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
        if (parsed.node.protocolId != "vless" || parsed.node.transport != VpnTransport.XHTTP ||
            parsed.node.security != VpnSecurity.TLS) {
            return SubscriptionService.ORDINARY
        }
        val extra = runCatching { JSONObject(params["extra"].orEmpty()) }.getOrNull()
        fun field(key: String): String = extra?.optString(key)?.takeIf { it.isNotBlank() }
            ?: params[key].orEmpty()
        val providerMarkers = params["encryption"].orEmpty().startsWith("mlkem768x25519plus.") &&
            params["mode"] == "packet-up" && field("uplinkHTTPMethod") == "GET" &&
            field("uplinkDataPlacement") == "body" && field("sessionIDKey") == "auth" &&
            field("seqKey") == "chunk_id"
        val cdnLabel = Regex("(?i)(^|[^a-z])cdn([^a-z]|$)").containsMatchIn(parsed.node.name)
        return if (providerMarkers || cdnLabel) SubscriptionService.CDN else SubscriptionService.ORDINARY
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)
}
