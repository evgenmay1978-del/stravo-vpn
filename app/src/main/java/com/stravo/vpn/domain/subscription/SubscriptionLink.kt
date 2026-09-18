package com.stravo.vpn.domain.subscription

import com.stravo.vpn.domain.model.VpnSecurity
import com.stravo.vpn.domain.model.VpnTransport

/**
 * Безопасная карточка узла подписки. В ней НЕТ хоста, UUID, пароля и полного конфига —
 * только то, что можно показывать и логировать.
 */
data class SubscriptionNode(
    val id: String,
    val name: String,
    val protocolId: String,
    val protocolLabel: String,
    val transport: VpnTransport,
    val security: VpnSecurity,
) {
    val transportLabel: String get() = transport.displayName
    val securityLabel: String? get() = security.displayName.takeIf { security != VpnSecurity.NONE }

    /**
     * Умеет ли ядро этой сборки такой транспорт. XHTTP поддержан: ядро собирается из
     * форка sing-box-lx с тегом `with_xhttp`. Пометка остаётся для транспортов, которые
     * не распознал сам разбор ссылки.
     */
    val supportedByCore: Boolean get() = transport != VpnTransport.UNKNOWN
}

/** Почему строка не распознана. Готовый текст для пользователя собирается в UI. */
enum class Unrecognized {
    /** Пустая строка. */
    EMPTY,

    /** Строка длиннее разумного предела. */
    TOO_LONG,

    /** В строке пробелы или переносы: это не одна ссылка. */
    NOT_A_SINGLE_LINK,

    /** Схемы нет и на голый домен это не похоже. */
    NO_SCHEME,

    /** Схема есть, но приложение её не знает. */
    UNKNOWN_SCHEME,

    /** Схема протокола верная, а сам ключ разобрать не удалось. */
    BROKEN_KEY,

    /** Ссылка-обёртка клиента без вложенного адреса подписки. */
    BROKEN_IMPORT_LINK,
}

/** Что получилось из введённой строки. */
sealed interface ParsedLink {

    /**
     * Узел. [secretConfig] — полный конфиг: он уходит только в защищённое хранилище
     * и никогда не попадает ни в UI-модели, ни в логи.
     */
    data class Node(
        val node: SubscriptionNode,
        val secretConfig: String,
    ) : ParsedLink

    /** Ссылка на подписку: её содержимое нужно скачать. */
    data class SubscriptionUrl(val url: String) : ParsedLink

    /**
     * Строка не распознана. [reason] объясняет причину, [token] — что именно
     * не распознано (обычно схема), чтобы ошибка была предметной.
     */
    data class Unknown(val reason: Unrecognized, val token: String? = null) : ParsedLink
}

/** Результат импорта: безопасные метаданные плюс узлы. */
data class ImportedSubscription(
    val planName: String?,
    val activeUntil: String?,
    val nodes: List<SubscriptionNode>,
)
