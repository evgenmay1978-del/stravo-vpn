package com.stravo.vpn.domain.subscription

/**
 * Подписка обычно приходит одним телом: либо список ссылок строками, либо тот же список,
 * закодированный в base64. Декодер терпим к обоим вариантам и к url-safe алфавиту.
 */
object SubscriptionPayload {

    fun split(body: String): List<String> {
        val text = body.trim()
        if (text.isEmpty()) return emptyList()
        val decoded = if (looksLikePlainList(text)) null else Base64Codec.decodeOrNull(text)
        val source = decoded ?: text
        return source
            .split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
    }

    private fun looksLikePlainList(text: String): Boolean =
        text.contains("://") && !text.contains(' ')
}

/**
 * Минимальный base64-декодер на чистой Kotlin: java.util.Base64 недоступен на API 23–25,
 * а android.util.Base64 утянул бы доменный слой в Android-зависимости.
 */
object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decodeOrNull(input: String): String? {
        val cleaned = input
            .filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
            .replace('-', '+')
            .replace('_', '/')
        if (cleaned.isEmpty()) return null

        val padded = when (cleaned.length % 4) {
            0 -> cleaned
            2 -> cleaned + "=="
            3 -> cleaned + "="
            else -> return null
        }

        val out = java.io.ByteArrayOutputStream(padded.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (char in padded) {
            if (char == '=') break
            val value = ALPHABET.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) return null
        return String(bytes, Charsets.UTF_8)
    }
}

