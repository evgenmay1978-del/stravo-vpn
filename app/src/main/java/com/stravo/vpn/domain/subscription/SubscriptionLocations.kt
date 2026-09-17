package com.stravo.vpn.domain.subscription

import com.stravo.vpn.domain.model.LocationsCatalog
import com.stravo.vpn.domain.model.VpnLocation
import com.stravo.vpn.domain.model.VpnTransport

/**
 * Превращает узлы подписки в локации экрана «Выбор локации».
 * Наружу выходят только страна, город, флаг и протокол — ни хоста, ни ключей.
 */
object SubscriptionLocations {

    fun from(nodes: List<SubscriptionNode>): List<VpnLocation> = nodes.map { node ->
        val flag = flagOf(node.name)
        val label = cleanName(node.name)
        val known = LocationsCatalog.match(label)
        VpnLocation(
            id = node.id,
            country = known?.country ?: label.ifEmpty { node.protocolLabel },
            city = known?.city ?: subtitleOf(node),
            flag = known?.flag ?: flag ?: DEFAULT_FLAG,
            recommended = false,
            aliases = emptyList(),
        )
    }

    private fun subtitleOf(node: SubscriptionNode): String = buildString {
        append(node.protocolLabel)
        if (node.transport != VpnTransport.UNKNOWN) {
            append(" · ")
            append(node.transport.displayName)
        }
        node.securityLabel?.let {
            append(" · ")
            append(it)
        }
    }

    /** Убирает флаг и служебные разделители из названия сервера. */
    fun cleanName(name: String): String {
        val withoutFlag = removeFlag(name)
        return withoutFlag
            .trim()
            .trim('-', '|', '·', '_', '—', '–', '(', ')')
            .trim()
            .replace(Regex("\\s{2,}"), " ")
            .take(NAME_LIMIT)
    }

    private fun removeFlag(name: String): String {
        val builder = StringBuilder(name.length)
        var index = 0
        while (index < name.length) {
            val codePoint = name.codePointAt(index)
            val size = Character.charCount(codePoint)
            if (codePoint in FLAG_START..FLAG_END) {
                index += size
                continue
            }
            builder.appendRange(name, index, index + size)
            index += size
        }
        return builder.toString()
    }

    fun flagOf(name: String): String? {
        var index = 0
        var pendingStart = -1
        while (index < name.length) {
            val codePoint = name.codePointAt(index)
            val size = Character.charCount(codePoint)
            if (codePoint in FLAG_START..FLAG_END) {
                if (pendingStart >= 0 && pendingStart + 2 == index) {
                    return name.substring(pendingStart, index + size)
                }
                pendingStart = index
            } else if (!Character.isWhitespace(codePoint)) {
                pendingStart = -1
            }
            index += size
        }
        return null
    }

    private const val FLAG_START = 0x1F1E6
    private const val FLAG_END = 0x1F1FF
    private const val NAME_LIMIT = 42
    private const val DEFAULT_FLAG = "🌐"
}

