package com.stravo.vpn.engine.box

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Authenticated loopback control. Probe requests are performed by the selected outbound,
 * never by an ordinary app socket that could bypass the VPN. No URLs/configs are logged. */
class CoreControl(private val secret: String) {
    fun delay(tag: String = "proxy", target: String = DEFAULT_PROBE, timeoutMs: Int = 5000): Int? =
        request("/proxies/" + encode(tag) + "/delay?timeout=" + timeoutMs + "&url=" + encode(target),
            timeoutMs + 1500)?.optInt("delay", -1)?.takeIf { it >= 0 }

    fun selectedNode(): String? = request("/proxies/proxy", 2000)
        ?.optString("now")?.removePrefix(NODE_PREFIX)?.takeIf { it.isNotBlank() && it != "proxy" }

    fun select(nodeId: String): Boolean =
        request("/proxies/proxy", 2000, JSONObject().put("name", tag(nodeId)).toString()) != null

    fun delays(): Map<String, Int> {
        val proxies = request("/proxies", 2000)?.optJSONObject("proxies") ?: return emptyMap()
        return proxies.keys().asSequence().filter { it.startsWith(NODE_PREFIX) }.mapNotNull { tag ->
            val history = proxies.optJSONObject(tag)?.optJSONArray("history") ?: return@mapNotNull null
            val last = history.optJSONObject(history.length() - 1)?.optInt("delay", -1) ?: -1
            if (last > 0) tag.removePrefix(NODE_PREFIX) to last else null
        }.toMap()
    }

    private fun request(path: String, timeoutMs: Int, payload: String? = null): JSONObject? {
        val connection = (URL("http://127.0.0.1:" + SingBoxConfigBuilder.CLASH_API_PORT + path)
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 1500
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("Authorization", "Bearer " + secret)
            if (payload != null) {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (payload != null) connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            when (connection.responseCode) {
                204 -> JSONObject()
                200 -> JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                else -> null
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val NODE_PREFIX = "node-"
        const val DEFAULT_PROBE = "https://www.gstatic.com/generate_204"
        fun tag(nodeId: String): String = NODE_PREFIX + nodeId
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
