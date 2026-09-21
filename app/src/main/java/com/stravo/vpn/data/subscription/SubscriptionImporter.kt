package com.stravo.vpn.data.subscription

import com.stravo.vpn.data.secret.SecretStore
import com.stravo.vpn.domain.model.Subscription
import com.stravo.vpn.domain.subscription.ParsedLink
import com.stravo.vpn.domain.subscription.SubscriptionLinkParser
import com.stravo.vpn.domain.subscription.SubscriptionNode
import com.stravo.vpn.domain.subscription.SubscriptionPayload
import com.stravo.vpn.domain.subscription.Unrecognized
import com.stravo.vpn.domain.subscription.SubscriptionService
import com.stravo.vpn.domain.subscription.SubscriptionServiceClassifier
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.domain.policy.CapabilityPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Что именно пошло не так при добавлении подписки. Тексты живут в ресурсах, не здесь. */
enum class ImportError {
    EMPTY,
    UNKNOWN_LINK,
    NETWORK,
    EMPTY_PAYLOAD,
    NO_NODES,
    TOO_MANY_NODES,
    SECRET_STORE,
    TV_CDN_ONLY,
    LOGIN_REJECTED,
    DEVICE_LIMIT,
    SUBSCRIPTION_EXPIRED,
    UNSUPPORTED_CONFIG,
}

sealed interface ImportOutcome {
    data class Success(
        val subscription: Subscription,
        val nodes: List<SubscriptionNode>,
    ) : ImportOutcome {
        val sourceIds: Set<String> get() = nodes.map { it.sourceId }.filter { it.isNotBlank() }.toSet()
    }

    /** [reason] и [token] уточняют ошибку: что именно не распознано (обычно схема). */
    data class Failure(
        val error: ImportError,
        val reason: Unrecognized? = null,
        val token: String? = null,
    ) : ImportOutcome
}

/**
 * Добавление подписки: принимает ссылку подписки (https), одиночный ключ (vless://, anytls://,
 * hysteria2://, trojan://, ss://) или тело подписки целиком.
 *
 * Секреты не покидают этот класс: полные конфиги уходят только в [SecretStore],
 * наружу отдаются безопасные карточки узлов.
 */
class SubscriptionImporter(
    private val secrets: SecretStore,
    private val formFactor: FormFactor,
    private val accountAccess: SubscriptionAccountAccess,
) {

    suspend fun import(
        raw: String,
        servicesToRefresh: Set<SubscriptionService>? = null,
        sourceId: String? = null,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val value = raw.trim()
        if (value.isEmpty()) return@withContext ImportOutcome.Failure(ImportError.EMPTY)
        if (value.length > MAX_BODY_CHARS) return@withContext ImportOutcome.Failure(
            ImportError.UNKNOWN_LINK, Unrecognized.TOO_LONG,
        )

        val source = try { when (val parsed = SubscriptionLinkParser.parse(value)) {
            is ParsedLink.Node -> Source(listOf(parsed.secretConfig), SubscriptionMetadata.read(""), null)

            is ParsedLink.SubscriptionUrl -> {
                if (formFactor.isTv && SubscriptionServiceClassifier.isCdnSource(parsed.url)) {
                    return@withContext ImportOutcome.Failure(ImportError.TV_CDN_ONLY)
                }
                val remote = fetch(parsed.url) ?: return@withContext ImportOutcome.Failure(ImportError.NETWORK)
                Source(linksFrom(remote.body), remote.subscription, parsed.url,
                    SubscriptionServiceClassifier.isCdnSource(parsed.url) || remote.isCdn)
            }

            is ParsedLink.Unknown -> {
                // Вставили тело подписки целиком: строки, base64 или JSON-конфиг Xray.
                val lines = linksFrom(value)
                if (lines.isNotEmpty() && lines.any { SubscriptionLinkParser.parse(it) is ParsedLink.Node }) {
                    Source(lines, SubscriptionMetadata.read(value), null)
                } else {
                    return@withContext ImportOutcome.Failure(
                        error = ImportError.UNKNOWN_LINK,
                        reason = parsed.reason,
                        token = parsed.token,
                    )
                }
            }
        }

        } catch (unsupported: UnsupportedPayload) {
            return@withContext ImportOutcome.Failure(ImportError.UNSUPPORTED_CONFIG, token = unsupported.safeReason)
        }

        if (source.links.isEmpty()) return@withContext ImportOutcome.Failure(ImportError.EMPTY_PAYLOAD)
        if (source.links.size > MAX_NODES) return@withContext ImportOutcome.Failure(ImportError.TOO_MANY_NODES)
        if (!secrets.isAvailable) return@withContext ImportOutcome.Failure(ImportError.SECRET_STORE)
        if (!SubscriptionSourceSecrets.migrate(secrets, emptySet())) {
            return@withContext ImportOutcome.Failure(ImportError.SECRET_STORE)
        }
        val refreshing = sourceId?.let { SubscriptionSourceSecrets.find(secrets, it) }
        if (sourceId != null && (refreshing == null || source.sourceUrl == null ||
                SubscriptionSourceSecrets.urlIdentity(refreshing.url) !=
                SubscriptionSourceSecrets.urlIdentity(source.sourceUrl))) {
            return@withContext ImportOutcome.Failure(ImportError.UNKNOWN_LINK)
        }

        val nodes = ArrayList<SubscriptionNode>(source.links.size)
        val configs = LinkedHashMap<String, String>()
        val localIdentity = source.links.distinct().sorted().joinToString("\n")
        val sourceIds = mutableMapOf<SubscriptionService, String>()
        for (link in source.links) {
            val parsed = SubscriptionLinkParser.parse(link)
            if (parsed !is ParsedLink.Node) continue
            val service = if (source.isCdn) SubscriptionService.CDN
                else SubscriptionServiceClassifier.classify(parsed.secretConfig)
            if (!CapabilityPolicy.permits(service, formFactor)) continue
            if (servicesToRefresh != null && service !in servicesToRefresh) continue
            if (refreshing != null && refreshing.service != service) continue
            val ownerId = sourceId ?: sourceIds.getOrPut(service) {
                SubscriptionSourceSecrets.idFor(service, source.sourceUrl, localIdentity)
            }
            val nodeId = "$ownerId.${parsed.node.id}"
            if (configs.containsKey(nodeId)) continue
            configs[nodeId] = parsed.secretConfig
            nodes.add(parsed.node.copy(id = nodeId, service = service, sourceId = ownerId))
        }
        if (nodes.isEmpty()) return@withContext ImportOutcome.Failure(
            if (formFactor.isTv && source.links.any {
                source.isCdn || SubscriptionServiceClassifier.classify(it) == SubscriptionService.CDN
            }) ImportError.TV_CDN_ONLY else ImportError.NO_NODES,
        )

        for ((id, config) in configs) {
            if (!secrets.put(id, config)) return@withContext ImportOutcome.Failure(ImportError.SECRET_STORE)
        }

        // Manual keys/bodies remain independent local sources, without an inferred refresh URL.
        val urls = source.sourceUrl?.let { url -> nodes.distinctBy { it.sourceId }.map {
            SubscriptionSourceSecrets.Entry(it.sourceId, it.service, url)
        } }.orEmpty()
        if (urls.isNotEmpty() && !SubscriptionSourceSecrets.put(secrets, urls)) {
            return@withContext ImportOutcome.Failure(ImportError.SECRET_STORE)
        }

        ImportOutcome.Success(
            subscription = source.subscription,
            nodes = nodes,
        )
    }

    /**
     * Тело подписки → список ссылок. Панели отдают три формата: список ссылок,
     * base64 от него, JSON-конфиги Xray (?format=xray) и Clash/mihomo (?format=mihomo) —
     * последние декодируются в обычные share-ссылки, дальше путь один и тот же.
     */
    private fun linksFrom(body: String): List<String> {
        val plain = com.stravo.vpn.domain.subscription.Base64Codec.decodeOrNull(body.trim()) ?: body
        if (PanelSubscription.looksLikeJson(plain) ||
            com.stravo.vpn.domain.subscription.WireGuardProfile.looksLikeConf(plain)) {
            return when (val result = PanelSubscription.convert(plain)) {
                is PanelSubscription.Conversion.Links -> result.links
                is PanelSubscription.Conversion.Unsupported -> throw UnsupportedPayload(result.reason)
                PanelSubscription.Conversion.Broken -> emptyList()
            }
        }
        return SubscriptionPayload.split(body)
    }

    private class UnsupportedPayload(val safeReason: String) : Exception()

    /** Конфиг узла для ядра туннеля. В UI и в логи это значение не попадает. */
    fun configFor(nodeId: String): String? = secrets.get(nodeId)

    private fun fetch(url: String): Remote? {
        var connection: HttpURLConnection? = null
        return try {
            val opened = URL(accountAccess.subscriptionUrl(url)).openConnection() as? HttpURLConnection ?: return null
            connection = opened
            opened.connectTimeout = CONNECT_TIMEOUT_MS
            opened.readTimeout = READ_TIMEOUT_MS
            opened.instanceFollowRedirects = true
            opened.requestMethod = "GET"
            opened.setRequestProperty("User-Agent", USER_AGENT)
            opened.setRequestProperty("Accept", "*/*")
            val code = opened.responseCode
            if (code !in 200..299) return null
            val body = opened.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > MAX_BODY_CHARS) return null
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            Remote(
                body = body,
                subscription = SubscriptionMetadata.read(body) { opened.getHeaderField(it) },
                isCdn = SubscriptionServiceClassifier.isCdnSource(opened.url.toString()),
            )
        } catch (error: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Ссылка подписки, из которой пришли узлы: секрет, живёт только в хранилище. */
    val sourceUrls: List<String> get() = SubscriptionSourceSecrets.entries(secrets).orEmpty()
        .filter { CapabilityPolicy.permits(it.service, formFactor) }.map { it.url }.distinct()

    fun hasSourceUrl(sourceId: String): Boolean = SubscriptionSourceSecrets.find(secrets, sourceId)
        ?.let { CapabilityPolicy.permits(it.service, formFactor) } == true

    /** Resolve the bearer URL inside the importer; callers only pass a public source id. */
    suspend fun refreshSource(sourceId: String): ImportOutcome {
        val entry = SubscriptionSourceSecrets.find(secrets, sourceId)
            ?: return ImportOutcome.Failure(ImportError.UNKNOWN_LINK)
        if (!CapabilityPolicy.permits(entry.service, formFactor)) {
            return ImportOutcome.Failure(if (formFactor.isTv && entry.service == SubscriptionService.CDN)
                ImportError.TV_CDN_ONLY else ImportError.NO_NODES)
        }
        return import(entry.url, setOf(entry.service), sourceId)
    }

    /**
     * Обновление подписки из сохранённого источника — без повторного ввода ссылки.
     * Каждый сохранённый источник обновляется независимо; ошибка сохраняет текущие узлы.
     */
    suspend fun refresh(url: String): ImportOutcome {
        val owned = SubscriptionSourceSecrets.entries(secrets).orEmpty().filter {
            SubscriptionSourceSecrets.urlIdentity(it.url) == SubscriptionSourceSecrets.urlIdentity(url)
        }.map { it.service }.toSet()
        return import(url, owned.takeIf { it.isNotEmpty() })
    }

    /** Забыть источник: вызывается при удалении подписки. */
    fun forgetSource(): Boolean = SubscriptionSourceSecrets.clear(secrets)

    fun forgetSource(sourceId: String): Boolean = SubscriptionSourceSecrets.remove(secrets, sourceId)

    fun migrateSource(nodes: List<SubscriptionNode>) {
        SubscriptionSourceSecrets.migrate(secrets, nodes.map { it.service }.toSet())
    }

    private class Source(
        val links: List<String>,
        val subscription: Subscription,
        val sourceUrl: String?,
        val isCdn: Boolean = false,
    )

    private class Remote(val body: String, val subscription: Subscription, val isCdn: Boolean)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_BODY_CHARS = 512 * 1024
        const val MAX_NODES = 500
        const val USER_AGENT = "STRAVO-VPN/1.0 (Android)"
    }
}

/** One encrypted URL catalog, atomically committed by SecretStore; never exposed to UI state. */
internal object SubscriptionSourceSecrets {
    private const val LEGACY = "stravo.subscription.source"
    private const val CATALOG = "stravo.subscription.sources.v2"

    class Entry(val id: String, val service: SubscriptionService, val url: String)

    fun urlIdentity(url: String): String = runCatching {
        URI(url.trim().substringBefore('#')).normalize().toASCIIString()
    }.getOrDefault(url.trim().substringBefore('#'))

    fun idFor(service: SubscriptionService, url: String?, localIdentity: String = ""): String {
        val identity = if (url == null) "local:$localIdentity" else "url:${urlIdentity(url)}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${service.name}:$identity".toByteArray(Charsets.UTF_8))
        return "source." + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Synchronized
    fun entries(secrets: SecretStore): List<Entry>? {
        if (!secrets.contains(CATALOG)) return legacyEntries(secrets, emptySet())
        val raw = secrets.get(CATALOG) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Entry(item.getString("id"), SubscriptionService.valueOf(item.getString("service")),
                    item.getString("url"))
            }
        }.getOrNull()
    }

    fun find(secrets: SecretStore, sourceId: String): Entry? = entries(secrets)?.firstOrNull { it.id == sourceId }

    @Synchronized
    fun migrate(secrets: SecretStore, services: Set<SubscriptionService>): Boolean {
        if (secrets.contains(CATALOG)) return entries(secrets) != null
        // Do not replace inaccessible encrypted legacy data with an empty catalog.
        val keys = listOf(LEGACY) + SubscriptionService.entries.map { "$LEGACY.${it.name}" }
        if (keys.any { secrets.contains(it) && secrets.get(it) == null }) return false
        return write(secrets, legacyEntries(secrets, services))
    }

    @Synchronized
    fun put(secrets: SecretStore, values: List<Entry>): Boolean {
        val current = entries(secrets) ?: return false
        return write(secrets, (current + values).associateBy { it.id }.values.toList())
    }

    @Synchronized
    fun remove(secrets: SecretStore, sourceId: String): Boolean {
        val current = entries(secrets) ?: return false
        val removed = current.firstOrNull { it.id == sourceId }
        val remaining = current.filterNot { it.id == sourceId }
        if (!write(secrets, remaining)) return false
        if (removed != null) {
            val legacyKey = "$LEGACY.${removed.service.name}"
            if (secrets.get(legacyKey) == removed.url) secrets.remove(legacyKey)
            if (secrets.get(LEGACY) == removed.url && remaining.none { it.url == removed.url }) secrets.remove(LEGACY)
        }
        return true
    }

    @Synchronized
    fun clear(secrets: SecretStore): Boolean {
        if (!write(secrets, emptyList())) return false
        secrets.remove(LEGACY)
        SubscriptionService.entries.forEach { secrets.remove("$LEGACY.${it.name}") }
        return true
    }

    private fun legacyEntries(secrets: SecretStore, services: Set<SubscriptionService>): List<Entry> {
        val urls = SubscriptionService.entries.mapNotNull { service ->
            secrets.get("$LEGACY.${service.name}")?.let { service to it }
        }.toMap().toMutableMap()
        secrets.get(LEGACY)?.let { old ->
            val kinds = services.filter { it != SubscriptionService.UNKNOWN }.ifEmpty {
                listOf(if (SubscriptionServiceClassifier.isCdnSource(old)) SubscriptionService.CDN
                    else SubscriptionService.ORDINARY)
            }
            kinds.forEach { urls.getOrPut(it) { old } }
        }
        return urls.map { (service, url) -> Entry(idFor(service, url), service, url) }
    }

    private fun write(secrets: SecretStore, entries: List<Entry>): Boolean {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("service", it.service.name).put("url", it.url)) }
        // Retain legacy ciphertext as a recovery copy. Presence of this catalog (even []) retires it.
        return secrets.put(CATALOG, array.toString())
    }
}

