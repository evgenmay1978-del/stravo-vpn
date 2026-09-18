package com.stravo.vpn.domain.model

data class VpnLocation(
    val id: String,
    val country: String,
    val city: String,
    val flag: String,
    val recommended: Boolean = false,
    /** Написания, которые встречаются в названиях серверов подписки. */
    val aliases: List<String> = emptyList(),
    /** Протокол узла: «VLESS · TCP · Reality». Секретов не содержит. */
    val protocol: String? = null,
    /** Чего ядру этой сборки не хватает для узла (например, XHTTP) — честная пометка. */
    val limitation: String? = null,
) {
    /**
     * Вторая строка строки списка и карточки: город, протокол и ограничение.
     * Пустые части не оставляют висящих разделителей.
     */
    val subtitle: String
        get() = listOfNotNull(
            city.takeIf { it.isNotBlank() },
            protocol?.takeIf { it.isNotBlank() },
            limitation?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
}

/**
 * Витрина локаций. Статический список — это подсказка для пустого состояния;
 * как только подписка подключена, экран показывает реальные серверы из неё.
 */
object LocationsCatalog {

    val AUTO: VpnLocation = VpnLocation(
        id = "auto",
        country = "Авто",
        city = "Автоматический сервер",
        flag = "🌐",
        recommended = true,
    )

    val all: List<VpnLocation> = listOf(
        AUTO,
        VpnLocation("ru-msk", "Россия", "Москва", "🇷🇺", aliases = listOf("russia", "moscow", "msk", "россия", "москва")),
        VpnLocation("tr-ist", "Турция", "Стамбул", "🇹🇷", aliases = listOf("turkey", "turkiye", "istanbul", "ist", "турция", "стамбул")),
        VpnLocation("ae-dxb", "ОАЭ", "Дубай", "🇦🇪", aliases = listOf("uae", "emirates", "dubai", "dxb", "оаэ", "дубай")),
        VpnLocation("sg-sin", "Сингапур", "Сингапур · Восточная Азия", "🇸🇬", aliases = listOf("singapore", "sin", "сингапур")),
        VpnLocation("de-fra", "Германия", "Франкфурт", "🇩🇪", aliases = listOf("germany", "deutschland", "frankfurt", "fra", "германия", "франкфурт")),
        VpnLocation("fr-par", "Франция", "Париж", "🇫🇷", aliases = listOf("france", "paris", "par", "франция", "париж")),
        VpnLocation("us-nyc", "США", "Нью-Йорк", "🇺🇸", aliases = listOf("united states", "usa", "new york", "nyc", "сша", "нью-йорк")),
        VpnLocation("us-lax", "США", "Лос-Анджелес", "🇺🇸", aliases = listOf("los angeles", "lax", "лос-анджелес")),
        VpnLocation("nl-ams", "Нидерланды", "Амстердам", "🇳🇱", aliases = listOf("netherlands", "holland", "amsterdam", "ams", "нидерланды", "амстердам")),
        VpnLocation("gb-lon", "Великобритания", "Лондон", "🇬🇧", aliases = listOf("united kingdom", "britain", "england", "london", "lon", "великобритания", "лондон")),
    )

    fun byId(id: String?): VpnLocation? = all.firstOrNull { it.id == id }

    /**
     * Ищет локацию витрины по названию сервера из подписки.
     * Сначала совпадение по целому слову, потом по подстроке — так «Франкфурт-2»
     * и «Germany Frankfurt» одинаково попадают в Германию.
     */
    fun match(label: String): VpnLocation? {
        if (label.isBlank()) return null
        val normalized = label.lowercase()
        val tokens = normalized.split(TOKEN_SEPARATOR).filter { it.isNotEmpty() }.toSet()
        var best: VpnLocation? = null
        var bestScore = 0
        for (location in all) {
            if (location.id == AUTO.id) continue
            val keys = buildList {
                addAll(location.aliases)
                add(location.country.lowercase())
                add(location.city.substringBefore(' ').lowercase())
            }
            for (key in keys) {
                if (key.length < 2) continue
                val score = when {
                    tokens.contains(key) -> key.length * 2
                    key.length >= 4 && normalized.contains(key) -> key.length
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = location
                }
            }
        }
        return best
    }

    private val TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{Nd}]+")
}

