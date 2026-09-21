package com.stravo.vpn.engine.box

import android.content.Context

/** Only opaque node ids and user intent. No keys, source URLs or user identifiers. */
class TunnelSession(context: Context) {
    private val prefs = context.getSharedPreferences("stravo.tunnel.session", Context.MODE_PRIVATE)
    val wanted: Boolean get() = prefs.getBoolean("wanted", false)
    val nodeId: String? get() = prefs.getString("node", null)
    val automatic: Boolean get() = prefs.getBoolean("automatic", false)
    val sourceId: String? get() = prefs.getString("source", null)

    fun begin(nodeId: String, automatic: Boolean) {
        prefs.edit().putString("node", nodeId).putBoolean("automatic", automatic)
            .putBoolean("wanted", true).commit()
    }

    fun stop() {
        prefs.edit().putBoolean("wanted", false).commit()
    }

    fun updateNode(nodeId: String) {
        prefs.edit().putString("node", nodeId).apply()
    }

    fun rememberSource(sourceId: String) {
        prefs.edit().putString("source", sourceId).commit()
    }
}
