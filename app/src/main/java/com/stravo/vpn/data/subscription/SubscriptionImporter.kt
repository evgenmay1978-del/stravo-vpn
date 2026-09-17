package com.stravo.vpn.data.subscription

import com.stravo.vpn.data.secret.SecretStore
import com.stravo.vpn.domain.subscription.Base64Codec
import com.stravo.vpn.domain.subscription.ParsedLink
import com.stravo.vpn.domain.subscription.SubscriptionLinkParser
import com.stravo.vpn.domain.subscription.SubscriptionNode
import com.stravo.vpn.domain.subscription.SubscriptionPayload
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
}

sealed interface ImportOutcome {
    data class Success(
        val planName: String?,
        val activeUntil: String?,
        val nodes: List<SubscriptionNode>,
    ) : ImportOutcome

    data class Failure(val error: ImportError) : ImportOutcome
}

/**
 * Добавление подписки: принимает ссылку подписки (https), одиночный ключ (vless://, anytls://,
 * hysteria2://, trojan://, ss://) или тело подписки целиком.
 *
 * Секреты не покидают этот класс: полные конфиги уходят только в [SecretStore],
 * наружу отдаются безопасные карточки узлов.
 */
class SubscriptionImporter(private val secrets: SecretStore) {

    suspend fun import(raw: String): ImportOutcome = withContext(Dispatchers.IO) {
        val value = raw.trim()
        if (value.isEmpty()) return@withContext ImportOutcome.Failure(ImportError.EMPTY)

        val source = when (val parsed = SubscriptionLinkParser.parse(value)) {
            is ParsedLink.Node -> Source(listOf(parsed.secretConfig), null, null)

            is ParsedLink.SubscriptionUrl -> {
                val remote = fetch(parsed.url) ?: return@withContext ImportOutcome.Failure(ImportError.NETWORK)
                Source(SubscriptionPayload.split(remote.body), remote.planName, remote.activeUntil)
            }

            ParsedLink.Unknown -> return@withContext ImportOutcome.Failure(ImportError.UNKNOWN_LINK)
        }

        if (source.links.isEmpty()) return@withContext ImportOutcome.Failure(ImportError.EMPTY_PAYLOAD)
        if (source.links.size > MAX_NODES) return@withContext ImportOutcome.Failure(ImportError.TOO_MANY_NODES)
        if (!secrets.isAvailable) return@withContext ImportOutcome.Failure(ImportError.SECRET_STORE)

        val nodes = ArrayList<SubscriptionNode>(source.links.size)
        val configs = LinkedHashMap<String, String>()
        for (link in source.links) {
            val parsed = SubscriptionLinkParser.parse(link)
            if (parsed !is ParsedLink.Node) continue
            if (configs.containsKey(parsed.node.id)) continue
            configs[parsed.node.id] = parsed.secretConfig
            nodes.add(parsed.node)
        }
        if (nodes.isEmpty()) return@withContext ImportOutcome.Failure(ImportError.NO_NODES)

        for ((id, config) in configs) {
            if (!secrets.put(id, config)) return@withContext ImportOutcome.Failure(ImportError.SECRET_STORE)
        }

        ImportOutcome.Success(
            planName = source.planName,
            activeUntil = source.activeUntil,
            nodes = nodes,
        )
    }

    /** Конфиг узла для ядра туннеля. В UI и в логи это значение не попадает. */
    fun configFor(nodeId: String): String? = secrets.get(nodeId)

    private fun fetch(url: String): Remote? {
        var connection: HttpURLConnection? = null
        return try {
            val opened = URL(url).openConnection() as? HttpURLConnection ?: return null
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

    private class Source(val links: List<String>, val planName: String?, val activeUntil: String?)

    private class Remote(val body: String, val planName: String?, val activeUntil: String?)

    private companion object {
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

