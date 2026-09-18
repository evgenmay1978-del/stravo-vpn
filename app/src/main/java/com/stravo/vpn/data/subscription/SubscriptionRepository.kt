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

    private val stored: Stored = read()

    private val _subscription = MutableStateFlow(stored.subscription)
    private val _nodes = MutableStateFlow(stored.nodes)

    /** Версия конвертера, которой собраны сохранённые узлы, и время импорта. */
    private var importVersion: Int = stored.version
    private var importedAt: Long = stored.importedAt

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
        importVersion = CONVERTER_VERSION
        importedAt = System.currentTimeMillis()
        persist()
    }

    /**
     * Пора ли пересобрать узлы из источника: разбор ссылок изменился вместе с
     * приложением ([CONVERTER_VERSION]) или данные старше [REFRESH_AFTER_MS].
     *
     * Само обновление делает [com.stravo.vpn.ui.state.StravoViewModel] при старте:
     * репозиторий только отвечает на вопрос и ничего не ходит в сеть.
     */
    fun needsRefresh(now: Long = System.currentTimeMillis()): Boolean {
        if (_nodes.value.isEmpty()) return false
        if (importVersion < CONVERTER_VERSION) return true
        return importedAt <= 0L || now - importedAt > REFRESH_AFTER_MS
    }

    fun clear() {
        _nodes.value = emptyList()
        _subscription.value = Subscription.None
        persist()
    }

    // --- Хранение ---------------------------------------------------------

    /** commit(), а не apply(): ядро может уронить процесс сразу после импорта. */
    private fun persist() {
        val nodes = _nodes.value
        val subscription = _subscription.value
        val root = JSONObject()
            .put("plan", subscription.planName)
            .put("until", subscription.activeUntil ?: JSONObject.NULL)
            .put("nodes", encodeNodes(nodes))
            .put("version", CONVERTER_VERSION)
            .put("time", System.currentTimeMillis())
        prefs.edit().putString(KEY_STATE, root.toString()).commit()
    }

    private fun read(): Stored {
        val raw = prefs.getString(KEY_STATE, null)
            ?: return Stored(Subscription.None, emptyList(), 0, 0L)
        return try {
            val root = JSONObject(raw)
            val nodes = decodeNodes(root.optJSONArray("nodes"))
            val plan = root.optString("plan").takeIf { it.isNotBlank() } ?: DEFAULT_PLAN
            val until = root.optString("until").takeIf { it.isNotBlank() && it != "null" }
            val active = root.optBoolean("active", nodes.isNotEmpty()) && nodes.isNotEmpty()
            Stored(
                subscription = if (active) {
                    Subscription(planName = plan, activeUntil = until, isActive = true)
                } else {
                    Subscription.None
                },
                nodes = nodes,
                version = root.optInt("version", 0),
                importedAt = root.optLong("time", 0L),
            )
        } catch (error: Exception) {
            Stored(Subscription.None, emptyList(), 0, 0L)
        }
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

    private fun decodeNodes(array: JSONArray?): List<SubscriptionNode> {
        if (array == null) return emptyList()
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
        return result
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private class Stored(
        val subscription: Subscription,
        val nodes: List<SubscriptionNode>,
        val version: Int,
        val importedAt: Long,
    )

    companion object {
        /**
         * Версия разбора ссылок панели. Поднимается, когда меняется
         * [com.stravo.vpn.data.subscription.PanelSubscription] или
         * [com.stravo.vpn.engine.box.SingBoxConfigBuilder]: сохранённые узлы после
         * такого изменения нужно пересобрать из источника, иначе в конфиг годами
         * уходят старые параметры (так CDN-узлы отвечали 405).
         */
        const val CONVERTER_VERSION = 1

        /** Через сколько обновлять подписку из источника при старте приложения. */
        private const val REFRESH_AFTER_MS = 6 * 60 * 60 * 1000L
        private const val DEFAULT_PLAN = "STRAVO VPN"
        private const val KEY_STATE = "stravo.subscription.state"
        private const val PREFS = "stravo.subscription"
    }
}

