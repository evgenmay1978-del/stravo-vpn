package com.stravo.vpn.data.subscription

import com.stravo.vpn.data.secret.SecretStore
import com.stravo.vpn.domain.subscription.Base64Codec
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
}

sealed interface ImportOutcome {
    data class Success(
        val planName: String?,
        val activeUntil: String?,
        val nodes: List<SubscriptionNode>,
    ) : ImportOutcome

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
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val value = raw.trim()
        if (value.isEmpty()) return@withContext ImportOutcome.Failure(ImportError.EMPTY)

        val source = when (val parsed = SubscriptionLinkParser.parse(value)) {
            is ParsedLink.Node -> Source(listOf(parsed.secretConfig), null, null, null)

            is ParsedLink.SubscriptionUrl -> {
                val remote = fetch(parsed.url) ?: return@withContext ImportOutcome.Failure(ImportError.NETWORK)
                Source(linksFrom(remote.body), remote.planName, remote.activeUntil, parsed.url,
                    SubscriptionServiceClassifier.isCdnSource(parsed.url) || remote.isCdn)
            }

            is ParsedLink.Unknown -> {
                // Вставили тело подписки целиком: строки, base64 или JSON-конфиг Xray.
                val lines = linksFrom(value)
                if (lines.isNotEmpty() && lines.any { SubscriptionLinkParser.parse(it) is ParsedLink.Node }) {
                    Source(lines, null, null, null)
                } else {
                    return@withContext ImportOutcome.Failure(
                        error = ImportError.UNKNOWN_LINK,
                        reason = parsed.reason,
                        token = parsed.token,
                    )
                }
            }
        }

        if (source.links.isEmpty()) return@withContext ImportOutcome.Failure(ImportError.EMPTY_PAYLOAD)
        if (source.links.size > MAX_NODES) return@withContext ImportOutcome.Failure(ImportError.TOO_MANY_NODES)
        if (!secrets.isAvailable) return@withContext ImportOutcome.Failure(ImportError.SECRET_STORE)

        val nodes = ArrayList<SubscriptionNode>(source.links.size)
        val configs = LinkedHashMap<String, String>()
        for (link in source.links) {
            val parsed = SubscriptionLinkParser.parse(link)
            if (parsed !is ParsedLink.Node) continue
            val service = if (source.isCdn) SubscriptionService.CDN
                else SubscriptionServiceClassifier.classify(parsed.secretConfig)
            if (!CapabilityPolicy.permits(service, formFactor)) continue
            if (servicesToRefresh != null && service !in servicesToRefresh) continue
            if (configs.containsKey(parsed.node.id)) continue
            configs[parsed.node.id] = parsed.secretConfig
            nodes.add(parsed.node.copy(service = service))
        }
        if (nodes.isEmpty()) return@withContext ImportOutcome.Failure(
            if (formFactor.isTv && source.links.any {
                source.isCdn || SubscriptionServiceClassifier.classify(it) == SubscriptionService.CDN
            }) ImportError.TV_CDN_ONLY else ImportError.NO_NODES,
        )

        for ((id, config) in configs) {
            if (!secrets.put(id, config)) return@withContext ImportOutcome.Failure(ImportError.SECRET_STORE)
        }

        // Источник запоминаем только у успешного импорта: по нему подписка обновляется
        // без повторного ввода ссылки. Панель может менять параметры узлов (например,
        // метод отправки XHTTP), и сохранённые ссылки без обновления остаются старыми.
        // Ручной ключ и вставленное тело источником не считаются — иначе автообновление
        // подменило бы их подпиской.
        if (!rememberSources(source.sourceUrl, nodes.map { it.service }.toSet())) {
            return@withContext ImportOutcome.Failure(ImportError.SECRET_STORE)
        }

        ImportOutcome.Success(
            planName = source.planName,
            activeUntil = source.activeUntil,
            nodes = nodes,
        )
    }

    /**
     * Тело подписки → список ссылок. Панели отдают три формата: список ссылок,
     * base64 от него, JSON-конфиги Xray (?format=xray) и Clash/mihomo (?format=mihomo) —
     * последние декодируются в обычные share-ссылки, дальше путь один и тот же.
     */
    private fun linksFrom(body: String): List<String> {
        if (PanelSubscription.looksLikeJson(body)) {
            val links = PanelSubscription.toLinks(body)
            if (links.isNotEmpty()) return links
        }
        return SubscriptionPayload.split(body)
    }

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
            val body = opened.inputStream.bufferedReader().use { it.readText() }
            if (body.length > MAX_BODY_CHARS) return null
            Remote(
                body = body,
                planName = planNameOf(opened),
                activeUntil = expiryOf(opened.getHeaderField(HEADER_USERINFO)),
                isCdn = SubscriptionServiceClassifier.isCdnSource(opened.url.toString()),
            )
        } catch (error: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun planNameOf(connection: HttpURLConnection): String? {
        val raw = connection.getHeaderField(HEADER_TITLE)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val value = if (raw.startsWith(BASE64_PREFIX, ignoreCase = true)) {
            Base64Codec.decodeOrNull(raw.substringAfter(':'))
        } else {
            raw
        }
        return value?.trim()?.takeIf { it.isNotEmpty() }?.take(PLAN_NAME_LIMIT)
    }

    private fun expiryOf(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val expire = header.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith(EXPIRE_KEY, ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.toLongOrNull()
            ?: return null
        if (expire <= 0L) return null
        return try {
            SimpleDateFormat(DATE_PATTERN, Locale.US).format(Date(expire * 1000L))
        } catch (error: Exception) {
            null
        }
    }

    /** Ссылка подписки, из которой пришли узлы: секрет, живёт только в хранилище. */
    val sourceUrls: List<String> get() = SubscriptionService.entries.mapNotNull { service ->
        secrets.get(sourceKey(service))
    }.plus(listOfNotNull(secrets.get(SOURCE_ID))).distinct()

    /**
     * Обновление подписки из сохранённого источника — без повторного ввода ссылки.
     * Каждый сохранённый источник обновляется независимо; ошибка сохраняет текущие узлы.
     */
    suspend fun refresh(url: String): ImportOutcome {
        // A previously mixed source must not replace a newer separately added CDN subscription.
        val owned = SubscriptionService.entries.filter { secrets.get(sourceKey(it)) == url }.toSet()
        return import(url, owned.takeIf { it.isNotEmpty() })
    }

    /** Забыть источник: вызывается при удалении подписки. */
    fun forgetSource() {
        secrets.remove(SOURCE_ID)
        SubscriptionService.entries.forEach { secrets.remove(sourceKey(it)) }
    }

    private fun rememberSources(url: String?, services: Set<SubscriptionService>): Boolean {
        val previous = services.associateWith { secrets.get(sourceKey(it)) }
        for (service in services) {
            if (url.isNullOrBlank()) secrets.remove(sourceKey(service))
            else if (!secrets.put(sourceKey(service), url)) {
                previous.forEach { (kind, value) ->
                    if (value == null) secrets.remove(sourceKey(kind)) else secrets.put(sourceKey(kind), value)
                }
                return false
            }
        }
        // The original single-source entry is migrated before a user can add another set.
        secrets.remove(SOURCE_ID)
        return true
    }

    fun migrateSource(nodes: List<SubscriptionNode>) {
        val old = secrets.get(SOURCE_ID) ?: return
        val services = nodes.map { it.service }.filter { it != SubscriptionService.UNKNOWN }.toSet()
        if (services.isNotEmpty()) rememberSources(old, services)
    }

    private fun sourceKey(service: SubscriptionService): String = "$SOURCE_ID.${service.name}"

    private class Source(
        val links: List<String>,
        val planName: String?,
        val activeUntil: String?,
        val sourceUrl: String?,
        val isCdn: Boolean = false,
    )

    private class Remote(val body: String, val planName: String?, val activeUntil: String?, val isCdn: Boolean)

    private companion object {
        /**
         * Ключ источника в защищённом хранилище. Идентификаторы узлов — короткие
         * хеши, поэтому имя с точками не может с ними столкнуться.
         */
        const val SOURCE_ID = "stravo.subscription.source"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_BODY_CHARS = 512 * 1024
        const val MAX_NODES = 500
        const val PLAN_NAME_LIMIT = 48
        const val USER_AGENT = "STRAVO-VPN/1.0 (Android)"
        const val DATE_PATTERN = "dd.MM.yyyy"
        const val HEADER_USERINFO = "subscription-userinfo"
        const val HEADER_TITLE = "profile-title"
        const val BASE64_PREFIX = "base64:"
        const val EXPIRE_KEY = "expire="
    }
}

