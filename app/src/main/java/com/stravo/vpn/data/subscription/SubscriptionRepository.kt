package com.stravo.vpn.data.subscription

import android.content.Context
import com.stravo.vpn.domain.model.Subscription
import com.stravo.vpn.domain.model.VpnSecurity
import com.stravo.vpn.domain.model.VpnTransport
import com.stravo.vpn.domain.subscription.SubscriptionNode
import com.stravo.vpn.domain.subscription.SubscriptionService
import com.stravo.vpn.domain.subscription.SubscriptionServiceClassifier
import com.stravo.vpn.data.secret.SecretStore
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
class SubscriptionRepository(context: Context, private val secrets: SecretStore) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val stored: Stored = read()

    private val _subscription = MutableStateFlow(stored.subscription)
    private val _nodes = MutableStateFlow(stored.nodes)
    private val plans = stored.plans.toMutableMap()

    /** Версия конвертера, которой собраны сохранённые узлы, и время импорта. */
    private var importVersion: Int = stored.version
    private var importedAt: Long = stored.importedAt

    val subscription: StateFlow<Subscription> = _subscription.asStateFlow()

    /** Узлы текущей подписки: имя, протокол, транспорт. Без хостов и ключей. */
    val nodes: StateFlow<List<SubscriptionNode>> = _nodes.asStateFlow()

    init {
        // Save migrated service tags before the legacy source entry is retired.
        if (stored.version < CONVERTER_VERSION && stored.nodes.isNotEmpty()) persist()
    }

    fun setActive(planName: String, activeUntil: String?) {
        _subscription.value = Subscription(planName = planName, activeUntil = activeUntil, isActive = true)
        persist()
    }

    /** Результат реального импорта: подписка, дата окончания и узлы. */
    fun applyImport(planName: String?, activeUntil: String?, nodes: List<SubscriptionNode>) {
        if (nodes.isEmpty()) return
        val services = nodes.map { it.service }.toSet()
        val incomingIds = nodes.map { it.id }.toSet()
        _nodes.value = _nodes.value.filter { it.service !in services && it.id !in incomingIds } + nodes
        val imported = Subscription(
            planName = planName?.takeIf { it.isNotBlank() } ?: DEFAULT_PLAN,
            activeUntil = activeUntil,
            isActive = nodes.isNotEmpty(),
        )
        services.forEach { plans[it] = imported }
        _subscription.value = plans[SubscriptionService.ORDINARY]
            ?: plans[SubscriptionService.CDN] ?: imported
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

    /** Retry on the next ordinary launch when only part of the saved sources refreshed. */
    fun markRefreshPending() {
        importedAt = 0L
        persist()
    }

    fun clear() {
        _nodes.value = emptyList()
        _subscription.value = Subscription.None
        plans.clear()
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
            .put("plans", JSONObject().apply {
                plans.forEach { (service, plan) ->
                    put(service.name, JSONObject().put("plan", plan.planName)
                        .put("until", plan.activeUntil ?: JSONObject.NULL))
                }
            })
            .put("version", importVersion)
            .put("time", importedAt)
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
            val savedPlans = nodes.map { it.service }.distinct().associateWith { service ->
                val item = root.optJSONObject("plans")?.optJSONObject(service.name)
                Subscription(
                    planName = item?.optString("plan")?.takeIf { it.isNotBlank() } ?: plan,
                    activeUntil = item?.optString("until")?.takeIf { it.isNotBlank() && it != "null" } ?: until,
                    isActive = true,
                )
            }
            Stored(
                subscription = if (active) {
                    Subscription(planName = plan, activeUntil = until, isActive = true)
                } else {
                    Subscription.None
                },
                nodes = nodes,
                version = root.optInt("version", 0),
                importedAt = root.optLong("time", 0L),
                plans = savedPlans,
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
                    .put("security", node.security.name)
                    .put("service", node.service.name),
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
            // Old installations had no service tag: classify the stored key without changing it.
            val savedService = enumOf(item.optString("service"), SubscriptionService.UNKNOWN)
            val service = if (savedService != SubscriptionService.UNKNOWN) {
                savedService
            } else {
                secrets.get(id)?.let {
                    SubscriptionServiceClassifier.classify(it, secrets.get("stravo.subscription.source"))
                } ?: SubscriptionService.UNKNOWN
            }
            result.add(
                SubscriptionNode(
                    id = id,
                    name = item.optString("name"),
                    protocolId = item.optString("protocolId"),
                    protocolLabel = item.optString("protocolLabel"),
                    transport = enumOf(item.optString("transport"), VpnTransport.UNKNOWN),
                    security = enumOf(item.optString("security"), VpnSecurity.NONE),
                    service = service,
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
        val plans: Map<SubscriptionService, Subscription> = emptyMap(),
    )

    companion object {
        /**
         * Версия разбора ссылок панели. Поднимается, когда меняется
         * [com.stravo.vpn.data.subscription.PanelSubscription] или
         * [com.stravo.vpn.engine.box.SingBoxConfigBuilder]: сохранённые узлы после
         * такого изменения нужно пересобрать из источника, иначе в конфиг годами
         * уходят старые параметры (так CDN-узлы отвечали 405).
         */
        const val CONVERTER_VERSION = 2

        /** Через сколько обновлять подписку из источника при старте приложения. */
        private const val REFRESH_AFTER_MS = 6 * 60 * 60 * 1000L
        private const val DEFAULT_PLAN = "STRAVO VPN"
        private const val KEY_STATE = "stravo.subscription.state"
        private const val PREFS = "stravo.subscription"
    }
}

