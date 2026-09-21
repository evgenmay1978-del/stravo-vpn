package com.stravo.vpn.engine.box

import com.stravo.vpn.data.settings.StravoSettings
import com.stravo.vpn.domain.subscription.SubscriptionNode
import com.stravo.vpn.domain.subscription.WireGuardProfile
import org.json.JSONArray
import org.json.JSONObject

/** Only nodes from the explicitly selected source enter this group. */
object SingBoxGroupBuilder {
    fun build(
        nodes: List<SubscriptionNode>,
        selectedId: String,
        automatic: Boolean,
        configFor: (String) -> String?,
        variant: CoreVariant,
        settings: StravoSettings,
        apiSecret: String?,
    ): CoreConfig {
        var selectedLink = configFor(selectedId) ?: return CoreConfig.Broken
        var primary = SingBoxConfigBuilder.build(selectedLink, variant, settings.coreDirectMode, apiSecret)
        if (automatic && primary !is CoreConfig.Ready) {
            for (node in nodes) {
                val link = configFor(node.id) ?: continue
                val built = SingBoxConfigBuilder.build(link, variant, settings.coreDirectMode, apiSecret)
                if (built is CoreConfig.Ready) { primary = built; selectedLink = link; break }
            }
        }
        if (primary !is CoreConfig.Ready) return primary
        val config = JSONObject(primary.json)
        val outbounds = JSONArray()
        val endpoints = JSONArray()
        val tags = ArrayList<String>()
        fun profileDns(link: String): List<String> =
            if (settings.preferProfileDns && WireGuardProfile.isLink(link))
                runCatching { WireGuardProfile.dnsFor(link) }.getOrDefault(emptyList()) else emptyList()
        val selectedDns = profileDns(selectedLink)
        var tcpDnsRequired = false
        for (node in nodes) {
            val link = configFor(node.id) ?: continue
            // A single group shares DNS policy: incompatible profiles remain manually selectable.
            if (profileDns(link) != selectedDns) continue
            val tag = CoreControl.tag(node.id)
            val endpoint = SingBoxConfigBuilder.endpointFor(link, tag)
            if (endpoint != null) endpoints.put(endpoint)
            else {
                val outbound = SingBoxConfigBuilder.outboundFor(link, tag) ?: continue
                if (outbound.optString("type") == "http") tcpDnsRequired = true
                outbounds.put(outbound)
            }
            tags.add(tag)
        }
        // Endpoint-only or a complete native config may not have a standalone outbound.
        // Preserve its builder result rather than replacing it with a broken group.
        if ((automatic && tags.isNotEmpty()) || CoreControl.tag(selectedId) in tags) {
            outbounds.put(if (automatic) JSONObject()
                .put("type", "urltest").put("tag", "proxy").put("outbounds", JSONArray(tags))
                .put("url", settings.probeUrl).put("interval", "1m").put("tolerance", 50)
                .put("interrupt_exist_connections", true)
            else JSONObject()
                .put("type", "selector").put("tag", "proxy").put("outbounds", JSONArray(tags))
                .put("default", CoreControl.tag(selectedId)).put("interrupt_exist_connections", true))
            val originals = config.getJSONArray("outbounds")
            for (i in 0 until originals.length()) {
                val outbound = originals.getJSONObject(i)
                if (outbound.optString("tag") != "proxy") outbounds.put(outbound)
            }
            config.put("outbounds", outbounds)
            if (endpoints.length() > 0) config.put("endpoints", endpoints) else config.remove("endpoints")
        }
        NetworkPolicy.apply(config, if (selectedDns.isNotEmpty()) settings.copy(remoteDns = selectedDns.first()) else settings)
        // HTTP CONNECT has no UDP relay. Preserve the chosen DNS address but use TCP.
        if (tcpDnsRequired) config.optJSONObject("dns")?.optJSONArray("servers")?.let { servers ->
            for (i in 0 until servers.length()) servers.optJSONObject(i)?.let {
                if (it.optString("tag") == "dns-proxy" && it.optString("type") == "udp") it.put("type", "tcp")
            }
        }
        return CoreConfig.Ready(config.toString())
    }
}
