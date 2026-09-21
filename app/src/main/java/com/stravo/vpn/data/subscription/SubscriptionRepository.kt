package com.stravo.vpn.data.subscription

import android.content.Context
import com.stravo.vpn.data.secret.SecretStore
import com.stravo.vpn.domain.model.Subscription
import com.stravo.vpn.domain.model.SubscriptionSource
import com.stravo.vpn.domain.model.VpnSecurity
import com.stravo.vpn.domain.model.VpnTransport
import com.stravo.vpn.domain.policy.CapabilityPolicy
import com.stravo.vpn.domain.subscription.SubscriptionNode
import com.stravo.vpn.domain.subscription.SubscriptionService
import com.stravo.vpn.domain.subscription.SubscriptionServiceClassifier
import com.stravo.vpn.platform.DeviceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Public metadata only. URLs and complete node configurations stay in SecretStore. */
class SubscriptionRepository(context: Context, private val secrets: SecretStore) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val formFactor = DeviceType.formFactorOf(context.applicationContext)
    private val stored = read()
    // Retain disallowed sources on disk for lossless migration, but never expose them on TV.
    private var allSources = stored.sources
    private var allNodes = stored.nodes
    private var versions = stored.versions
    private var renamed = stored.renamed
    private var customIntervals = stored.customIntervals
    private var readable = stored.valid
    private val _sources = MutableStateFlow(visibleSources(allSources))
    private val _nodes = MutableStateFlow(visibleNodes(allNodes))
    private val _plans = MutableStateFlow(aggregate(allSources))
    private val _subscription = MutableStateFlow(primary(_plans.value))

    val sources: StateFlow<List<SubscriptionSource>> = _sources.asStateFlow()
    /** Includes disabled sources' nodes; callers filter using enabled sources and selectedSourceId. */
    val nodes: StateFlow<List<SubscriptionNode>> = _nodes.asStateFlow()
    /** Compatibility projection: latest enabled source per service, not a sum of unrelated quotas. */
    val subscriptions: StateFlow<Map<SubscriptionService, Subscription>> = _plans.asStateFlow()
    val subscription: StateFlow<Subscription> = _subscription.asStateFlow()

    init {
        if (stored.migrated) {
            // Retain the pre-migration public state alongside the already retained encrypted keys.
            val previous = prefs.getString(KEY_STATE, null)
            val snapshotKey = "stravo.migration.pre-multisource"
            val retained = previous == null || secrets.contains(snapshotKey) || secrets.put(snapshotKey, previous)
            if (retained) save() else readable = false
        }
    }

    /** After an authenticated restore with VPN stopped. No network, service or VPN side effects. */
    @Synchronized
    fun reload(): Boolean {
        val restored = read()
        if (!restored.valid) return false
        readable = true
        return if (restored.migrated) {
            save(restored.sources, restored.nodes, restored.versions, restored.renamed, restored.customIntervals)
        } else {
            publish(restored.sources, restored.nodes, restored.versions, restored.renamed, restored.customIntervals)
            true
        }
    }

    @Synchronized
    fun setActive(planName: String, activeUntil: String?): Boolean {
        val existing = _sources.value.firstOrNull { it.service == SubscriptionService.ORDINARY }
        val plan = (existing?.subscription ?: Subscription.None).copy(
            planName = planName, activeUntil = activeUntil, isActive = true,
        )
        val source = existing?.copy(subscription = plan)
            ?: SubscriptionSource("legacy.ordinary", SubscriptionService.ORDINARY, plan)
        return save(nextSources = allSources.filterNot { it.id == source.id } + source)
    }

    /**
     * Existing two-argument callers remain supported: importer nodes carry their source id.
     * True means metadata is durable. Do not show success when this returns false.
     */
    @Synchronized
    fun applyImport(
        subscription: Subscription,
        nodes: List<SubscriptionNode>,
        sourceId: String? = null,
        refreshedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        if (nodes.isEmpty() || nodes.any { !CapabilityPolicy.permits(it.service, formFactor) }) return false
        if (sourceId != null && sourceId.isBlank()) return false
        if (sourceId != null && nodes.map { it.service }.distinct().size != 1) return false
        val normalized = nodes.groupBy { it.service }.flatMap { (service, serviceNodes) ->
            val fallback = SubscriptionSourceSecrets.idFor(service, null, serviceNodes.map { it.id }.sorted().joinToString("\n"))
            serviceNodes.map { node ->
                node.copy(sourceId = sourceId ?: node.sourceId.takeIf { it.isNotBlank() } ?: fallback)
            }
        }
        val groups = normalized.groupBy { it.sourceId }
        if (groups.any { (id, group) ->
                group.map { it.service }.distinct().size != 1 ||
                    allSources.any { it.id == id && it.service != group.first().service }
            }) return false
        val imported = subscription.copy(
            isActive = true,
            updateIntervalHours = subscription.updateIntervalHours
                ?.takeIf { it in 0..SubscriptionSource.MAX_UPDATE_INTERVAL_HOURS },
            supportUrl = SubscriptionMetadata.safeHttpUrl(subscription.supportUrl),
            homepageUrl = SubscriptionMetadata.safeHttpUrl(subscription.homepageUrl),
            announcementUrl = SubscriptionMetadata.safeHttpUrl(subscription.announcementUrl),
        )
        val replacements = groups.map { (id, group) ->
            val previous = allSources.firstOrNull { it.id == id }
            SubscriptionSource(
                id = id,
                service = group.first().service,
                subscription = imported,
                enabled = previous?.enabled ?: true,
                name = if (id in renamed && previous != null) previous.name else imported.planName,
                updatedAt = refreshedAt.coerceAtLeast(0L),
                updateIntervalHours = if (id in customIntervals && previous != null) previous.updateIntervalHours
                    else imported.updateIntervalHours ?: previous?.updateIntervalHours
                        ?: SubscriptionSource.DEFAULT_UPDATE_INTERVAL_HOURS,
            )
        }
        val replacedIds = groups.keys
        val nextNodes = allNodes.filterNot { it.sourceId in replacedIds } + normalized.distinctBy { it.id }
        if (nextNodes.map { it.id }.distinct().size != nextNodes.size) return false
        val oldNodes = allNodes.filter { it.sourceId in replacedIds }
        if (!save(
                nextSources = allSources.filterNot { it.id in replacedIds } + replacements,
                nextNodes = nextNodes,
                nextVersions = versions + replacedIds.associateWith { CONVERTER_VERSION },
            )) return false
        // Persist replacement before removing obsolete secrets; legacy keys survive migration itself.
        val retained = allNodes.map { it.id }.toSet()
        oldNodes.filterNot { it.id in retained }.forEach { secrets.remove(it.id) }
        return true
    }

    fun hasSourceUrl(sourceId: String): Boolean = _sources.value.any { it.id == sourceId } &&
        SubscriptionSourceSecrets.find(secrets, sourceId) != null

    fun sourceName(sourceId: String): String? = _sources.value.firstOrNull { it.id == sourceId }?.name

    /** Data for parent scheduling, scoped to selected ids; this method never performs network I/O. */
    @Synchronized
    fun dueSources(
        sourceIds: Set<String>? = null,
        now: Long = System.currentTimeMillis(),
    ): List<SubscriptionSource> = _sources.value.filter { source ->
        source.enabled && source.updateIntervalHours > 0 &&
            (sourceIds == null || source.id in sourceIds) && hasSourceUrl(source.id) &&
            (source.isRefreshDue(now) || (versions[source.id] ?: 0) < CONVERTER_VERSION)
    }

    fun needsRefresh(now: Long = System.currentTimeMillis()): Boolean = dueSources(now = now).isNotEmpty()

    @Synchronized
    fun renameSource(sourceId: String, name: String): Boolean {
        val source = _sources.value.firstOrNull { it.id == sourceId } ?: return false
        val clean = name.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }
            .trim().take(128)
        return save(
            nextSources = allSources.map {
                if (it.id == sourceId) it.copy(name = clean.ifBlank { source.subscription.planName }) else it
            },
            nextRenamed = if (clean.isBlank()) renamed - sourceId else renamed + sourceId,
        )
    }

    @Synchronized
    fun setSourceEnabled(sourceId: String, enabled: Boolean): Boolean {
        if (_sources.value.none { it.id == sourceId }) return false
        return save(nextSources = allSources.map { if (it.id == sourceId) it.copy(enabled = enabled) else it })
    }

    /** Null restores the provider interval; zero turns off scheduled refresh for this source. */
    @Synchronized
    fun setSourceUpdateIntervalHours(sourceId: String, hours: Int?): Boolean {
        val source = _sources.value.firstOrNull { it.id == sourceId } ?: return false
        if (hours != null && hours !in 0..SubscriptionSource.MAX_UPDATE_INTERVAL_HOURS) return false
        return save(
            nextSources = allSources.map {
                if (it.id == sourceId) it.copy(updateIntervalHours = hours ?: source.subscription.updateIntervalHours
                    ?: SubscriptionSource.DEFAULT_UPDATE_INTERVAL_HOURS) else it
            },
            nextCustomIntervals = if (hours == null) customIntervals - sourceId else customIntervals + sourceId,
        )
    }

    @Synchronized
    fun markSourceRefreshed(sourceId: String, refreshedAt: Long = System.currentTimeMillis()): Boolean {
        if (_sources.value.none { it.id == sourceId }) return false
        return save(
            nextSources = allSources.map { if (it.id == sourceId) it.copy(updatedAt = refreshedAt.coerceAtLeast(0L)) else it },
            nextVersions = versions + (sourceId to CONVERTER_VERSION),
        )
    }

    /** Call with the failed source id; the no-argument legacy call marks enabled sources pending. */
    @Synchronized
    fun markRefreshPending(sourceId: String? = null): Boolean {
        if (sourceId != null && _sources.value.none { it.id == sourceId }) return false
        val targets = _sources.value.filter { it.enabled && (sourceId == null || it.id == sourceId) }.map { it.id }.toSet()
        return save(nextSources = allSources.map { if (it.id in targets) it.copy(updatedAt = 0L) else it })
    }

    @Synchronized
    fun removeSource(sourceId: String): Boolean {
        if (_sources.value.none { it.id == sourceId }) return false
        val url = SubscriptionSourceSecrets.find(secrets, sourceId)
        if (!SubscriptionSourceSecrets.remove(secrets, sourceId)) return false
        val removedNodes = allNodes.filter { it.sourceId == sourceId }
        if (!save(
                nextSources = allSources.filterNot { it.id == sourceId },
                nextNodes = allNodes.filterNot { it.sourceId == sourceId },
                nextVersions = versions - sourceId,
                nextRenamed = renamed - sourceId,
                nextCustomIntervals = customIntervals - sourceId,
            )) {
            if (url != null) SubscriptionSourceSecrets.put(secrets, listOf(url))
            return false
        }
        val retained = allNodes.map { it.id }.toSet()
        removedNodes.filterNot { it.id in retained }.forEach { secrets.remove(it.id) }
        return true
    }

    @Synchronized
    fun clear(): Boolean {
        val urls = SubscriptionSourceSecrets.entries(secrets) ?: return false
        if (!SubscriptionSourceSecrets.clear(secrets)) return false
        val oldNodes = allNodes
        if (!save(emptyList(), emptyList(), emptyMap(), emptySet(), emptySet())) {
            SubscriptionSourceSecrets.put(secrets, urls)
            return false
        }
        oldNodes.forEach { secrets.remove(it.id) }
        return true
    }

    private fun visibleSources(values: List<SubscriptionSource>) =
        values.filter { CapabilityPolicy.permits(it.service, formFactor) }

    private fun visibleNodes(values: List<SubscriptionNode>) =
        values.filter { CapabilityPolicy.permits(it.service, formFactor) }

    private fun aggregate(values: List<SubscriptionSource>): Map<SubscriptionService, Subscription> =
        visibleSources(values).filter { it.enabled }.groupBy { it.service }
            .mapValues { (_, sources) -> sources.maxBy { it.updatedAt }.subscription }

    private fun primary(plans: Map<SubscriptionService, Subscription>): Subscription =
        plans[SubscriptionService.ORDINARY] ?: plans[SubscriptionService.CDN] ?: Subscription.None

    /** Commit before publishing: an interrupted or failed write leaves the last durable state intact. */
    private fun save(
        nextSources: List<SubscriptionSource> = allSources,
        nextNodes: List<SubscriptionNode> = allNodes,
        nextVersions: Map<String, Int> = versions,
        nextRenamed: Set<String> = renamed,
        nextCustomIntervals: Set<String> = customIntervals,
    ): Boolean {
        if (!readable) return false
        val plans = aggregate(nextSources)
        val plan = primary(plans)
        val root = JSONObject().put("schema", 2)
            .put("plan", plan.planName).put("until", plan.activeUntil ?: JSONObject.NULL)
            .put("active", plan.isActive)
            .put("version", nextVersions.values.minOrNull() ?: CONVERTER_VERSION)
            .put("time", nextSources.minOfOrNull { it.updatedAt } ?: 0L)
            .put("plans", JSONObject().apply { plans.forEach { (service, value) -> put(service.name, encodePlan(value)) } })
            .put("sources", JSONArray().apply {
                nextSources.forEach { source ->
                    put(JSONObject().put("id", source.id).put("service", source.service.name)
                        .put("subscription", encodePlan(source.subscription)).put("enabled", source.enabled)
                        .put("name", source.name).put("updatedAt", source.updatedAt)
                        .put("updateIntervalHours", source.updateIntervalHours)
                        .put("converterVersion", nextVersions[source.id] ?: 0)
                        .put("customName", source.id in nextRenamed)
                        .put("customInterval", source.id in nextCustomIntervals))
                }
            })
            .put("nodes", JSONArray().apply {
                nextNodes.forEach { node ->
                    put(JSONObject().put("id", node.id).put("name", node.name)
                        .put("protocolId", node.protocolId).put("protocolLabel", node.protocolLabel)
                        .put("transport", node.transport.name).put("security", node.security.name)
                        .put("service", node.service.name).put("sourceId", node.sourceId))
                }
            })
        if (!prefs.edit().putString(KEY_STATE, root.toString()).commit()) return false
        publish(nextSources, nextNodes, nextVersions, nextRenamed, nextCustomIntervals)
        return true
    }

    private fun publish(
        nextSources: List<SubscriptionSource>,
        nextNodes: List<SubscriptionNode>,
        nextVersions: Map<String, Int>,
        nextRenamed: Set<String>,
        nextCustomIntervals: Set<String>,
    ) {
        allSources = nextSources
        allNodes = nextNodes
        versions = nextVersions
        renamed = nextRenamed
        customIntervals = nextCustomIntervals
        _sources.value = visibleSources(nextSources)
        _nodes.value = visibleNodes(nextNodes)
        _plans.value = aggregate(nextSources)
        _subscription.value = primary(_plans.value)
    }

    private fun read(): Stored {
        return try {
            val raw = prefs.getString(KEY_STATE, null)
            val root = raw?.let(::JSONObject) ?: JSONObject()
            val nodeArray = root.optJSONArray("nodes")
            if (root.has("nodes") && nodeArray == null) return Stored(valid = false)
            val nodes = decodeNodes(nodeArray)
            if (nodeArray != null && nodes.size != nodeArray.length()) return Stored(valid = false)
            val array = root.optJSONArray("sources")
            if ((root.has("sources") || root.optInt("schema", 0) >= 2) && array == null) return Stored(valid = false)
            if (array != null) {
                val sources = ArrayList<SubscriptionSource>()
                val versions = mutableMapOf<String, Int>()
                val renamed = mutableSetOf<String>()
                val intervals = mutableSetOf<String>()
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val id = item.getString("id")
                    val plan = decodePlan(item.getJSONObject("subscription"))
                    sources.add(SubscriptionSource(
                        id, enumOf(item.optString("service"), SubscriptionService.UNKNOWN), plan,
                        enabled = item.optBoolean("enabled", true),
                        name = item.optionalText("name") ?: plan.planName,
                        updatedAt = item.optLong("updatedAt", 0L).coerceAtLeast(0L),
                        updateIntervalHours = item.optInt("updateIntervalHours", SubscriptionSource.DEFAULT_UPDATE_INTERVAL_HOURS)
                            .coerceIn(0, SubscriptionSource.MAX_UPDATE_INTERVAL_HOURS),
                    ))
                    versions[id] = item.optInt("converterVersion", 0)
                    if (item.optBoolean("customName", false)) renamed.add(id)
                    if (item.optBoolean("customInterval", false)) intervals.add(id)
                }
                if (sources.any { it.id.isBlank() } || sources.map { it.id }.distinct().size != sources.size ||
                    nodes.map { it.id }.distinct().size != nodes.size || nodes.any { node ->
                        sources.none { it.id == node.sourceId && it.service == node.service }
                    }) return Stored(valid = false)
                Stored(sources, nodes, versions, renamed, intervals)
            } else {
                val plans = root.optJSONObject("plans")
                val kinds = (nodes.map { it.service } + SubscriptionService.entries.filter { plans?.has(it.name) == true }).toSet()
                val migratedSecrets = SubscriptionSourceSecrets.migrate(secrets, kinds)
                val urls = SubscriptionSourceSecrets.entries(secrets).orEmpty()
                val services = (kinds + urls.map { it.service }).ifEmpty {
                    if (root.optionalText("plan") != null && root.optString("plan") != Subscription.None.planName)
                        setOf(SubscriptionService.ORDINARY) else emptySet()
                }
                val sources = services.map { service ->
                    val item = plans?.optJSONObject(service.name) ?: root
                    val plan = decodePlan(item, root.optionalText("plan") ?: DEFAULT_PLAN,
                        nodes.any { it.service == service })
                    SubscriptionSource(
                        id = urls.firstOrNull { it.service == service }?.id ?: ("legacy." + service.name.lowercase()),
                        service = service, subscription = plan, updatedAt = root.optLong("time", 0L).coerceAtLeast(0L),
                        updateIntervalHours = plan.updateIntervalHours ?: SubscriptionSource.DEFAULT_UPDATE_INTERVAL_HOURS,
                    )
                }
                Stored(
                    sources,
                    nodes.map { node -> node.copy(sourceId = sources.first { it.service == node.service }.id) },
                    sources.associate { it.id to root.optInt("version", 0) },
                    migrated = migratedSecrets && (raw != null || sources.isNotEmpty()),
                    valid = migratedSecrets,
                )
            }
        } catch (_: Exception) {
            // Do not rewrite unreadable state or delete secrets during recovery.
            Stored(valid = false)
        }
    }

    private fun encodePlan(plan: Subscription): JSONObject = JSONObject()
        .put("plan", plan.planName).put("until", plan.activeUntil ?: JSONObject.NULL).put("active", plan.isActive)
        .put("description", plan.description ?: JSONObject.NULL).put("announcement", plan.announcement ?: JSONObject.NULL)
        .put("usedBytes", plan.usedBytes ?: JSONObject.NULL).put("totalBytes", plan.totalBytes ?: JSONObject.NULL)
        .put("updateIntervalHours", plan.updateIntervalHours ?: JSONObject.NULL)
        .put("supportUrl", SubscriptionMetadata.safeHttpUrl(plan.supportUrl) ?: JSONObject.NULL)
        .put("homepageUrl", SubscriptionMetadata.safeHttpUrl(plan.homepageUrl) ?: JSONObject.NULL)
        .put("announcementUrl", SubscriptionMetadata.safeHttpUrl(plan.announcementUrl) ?: JSONObject.NULL)

    private fun decodePlan(item: JSONObject, fallback: String = DEFAULT_PLAN, active: Boolean = true) = Subscription(
        planName = item.optionalText("plan") ?: fallback,
        activeUntil = item.optionalText("until"),
        isActive = item.optBoolean("active", active),
        description = item.optionalText("description"),
        announcement = item.optionalText("announcement"),
        usedBytes = item.optionalLong("usedBytes"),
        totalBytes = item.optionalLong("totalBytes"),
        updateIntervalHours = item.optionalLong("updateIntervalHours")
            ?.takeIf { it <= SubscriptionSource.MAX_UPDATE_INTERVAL_HOURS }?.toInt(),
        supportUrl = SubscriptionMetadata.safeHttpUrl(item.optionalText("supportUrl")),
        homepageUrl = SubscriptionMetadata.safeHttpUrl(item.optionalText("homepageUrl")),
        announcementUrl = SubscriptionMetadata.safeHttpUrl(item.optionalText("announcementUrl")),
    )

    private fun decodeNodes(array: JSONArray?): List<SubscriptionNode> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optionalText("id") ?: return@mapNotNull null
            val saved = enumOf(item.optString("service"), SubscriptionService.UNKNOWN)
            val service = if (saved != SubscriptionService.UNKNOWN) saved else secrets.get(id)?.let {
                SubscriptionServiceClassifier.classify(it, secrets.get("stravo.subscription.source"))
            } ?: SubscriptionService.UNKNOWN
            SubscriptionNode(
                id = id, name = item.optString("name"), protocolId = item.optString("protocolId"),
                protocolLabel = item.optString("protocolLabel"),
                transport = enumOf(item.optString("transport"), VpnTransport.UNKNOWN),
                security = enumOf(item.optString("security"), VpnSecurity.NONE),
                service = service, sourceId = item.optionalText("sourceId").orEmpty(),
            )
        }
    }

    private fun JSONObject.optionalText(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optionalLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key, -1L).takeIf { it >= 0 }

    private inline fun <reified T : Enum<T>> enumOf(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private class Stored(
        val sources: List<SubscriptionSource> = emptyList(),
        val nodes: List<SubscriptionNode> = emptyList(),
        val versions: Map<String, Int> = emptyMap(),
        val renamed: Set<String> = emptySet(),
        val customIntervals: Set<String> = emptySet(),
        val migrated: Boolean = false,
        val valid: Boolean = true,
    )

    companion object {
        /** Rebuild legacy per-source configurations with the current parser at the next permitted refresh. */
        const val CONVERTER_VERSION = 5
        private const val DEFAULT_PLAN = "STRAVO VPN"
        private const val KEY_STATE = "stravo.subscription.state"
        private const val PREFS = "stravo.subscription"
    }
}

