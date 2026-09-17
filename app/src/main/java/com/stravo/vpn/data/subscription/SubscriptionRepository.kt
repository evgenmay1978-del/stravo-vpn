package com.stravo.vpn.data.subscription

import android.content.Context
import com.stravo.vpn.domain.model.Subscription
import com.stravo.vpn.domain.model.VpnSecurity
import com.stravo.vpn.domain.model.VpnTransport
import com.stravo.vpn.domain.subscription.SubscriptionNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Репозиторий подписки.
 *
 * Хранит только безопасные метаданные (план, дата окончания, флаг активности)
 * и безопасные карточки узлов — они переживают перезапуск приложения.
 * Полные конфиги лежат в [com.stravo.vpn.data.secret.SecretStore] и сюда не попадают.
 */
class SubscriptionRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _subscription = MutableStateFlow(loadSubscription())
    private val _nodes = MutableStateFlow(loadNodes())

    val subscription: StateFlow<Subscription> = _subscription.asStateFlow()

    /** Узлы текущей подписки: имя, протокол, транспорт. Без хостов и ключей. */
    val nodes: StateFlow<List<SubscriptionNode>> = _nodes.asStateFlow()

    fun setActive(planName: String, activeUntil: String?) {
        _subscription.value = Subscription(planName = planName, activeUntil = activeUntil, isActive = true)
        persist()
    }

    /** Результат реального импорта: подписка, дата окончания и узлы. */
    fun applyImport(planName: String?, activeUntil: String?, nodes: List<SubscriptionNode>) {
        _nodes.value = nodes
        _subscription.value = Subscription(
            planName = planName?.takeIf { it.isNotBlank() } ?: DEFAULT_PLAN,
            activeUntil = activeUntil,
            isActive = nodes.isNotEmpty(),
        )
        persist()
    }

    fun clear() {
        _nodes.value = emptyList()
        _subscription.value = Subscription.None
        persist()
    }

    // --- Хранение ---------------------------------------------------------

    private fun persist() {
        val subscription = _subscription.value
        val root = JSONObject()
            .put("plan", subscription.planName)
            .put("until", subscription.activeUntil ?: JSONObject.NULL)
            .put("active", subscription.isActive)
            .put("nodes", encodeNodes(_nodes.value))
        prefs.edit().putString(KEY_STATE, root.toString()).apply()
    }

    private fun encodeNodes(nodes: List<SubscriptionNode>): JSONArray {
        val array = JSONArray()
        for (node in nodes) {
            array.put(
                JSONObject()
                    .put("id", node.id)
                    .put("name", node.name)
                    .put("protocolId", node.protocolId)
                    .put("protocolLabel", node.protocolLabel)
                    .put("transport", node.transport.name)
                    .put("security", node.security.name),
            )
        }
        return array
    }

    private fun loadNodes(): List<SubscriptionNode> {
        val raw = prefs.getString(KEY_STATE, null) ?: return emptyList()
        return try {
            val array = JSONObject(raw).optJSONArray("nodes") ?: return emptyList()
            val result = ArrayList<SubscriptionNode>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                result.add(
                    SubscriptionNode(
                        id = id,
                        name = item.optString("name"),
                        protocolId = item.optString("protocolId"),
                        protocolLabel = item.optString("protocolLabel"),
                        transport = enumOf(item.optString("transport"), VpnTransport.UNKNOWN),
                        security = enumOf(item.optString("security"), VpnSecurity.NONE),
                    ),
                )
            }
            result
        } catch (error: Exception) {
            emptyList()
        }
    }

    private fun loadSubscription(): Subscription {
        val raw = prefs.getString(KEY_STATE, null) ?: return Subscription.None
        return try {
            val root = JSONObject(raw)
            val active = root.optBoolean("active") && _nodes.value.isNotEmpty()
            Subscription(
                planName = root.optString("plan").takeIf { it.isNotBlank() } ?: DEFAULT_PLAN,
                activeUntil = root.optString("until").takeIf { it.isNotBlank() && it != "null" },
                isActive = active,
            )
        } catch (error: Exception) {
            Subscription.None
        }
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        const val DEFAULT_PLAN = "STRAVO VPN"
        const val KEY_STATE = "stravo.subscription.state"
        const val PREFS = "stravo.subscription"
    }
}

