package com.stravo.vpn.domain.model

data class VpnLocation(
    val id: String,
    val country: String,
    val city: String,
    val flag: String,
    val recommended: Boolean = false,
)

/** Каталог локаций. Пока это статическая витрина: реальные серверы приходят с подпиской. */
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
        VpnLocation("ru-msk", "Россия", "Москва", "🇷🇺"),
        VpnLocation("tr-ist", "Турция", "Стамбул", "🇹🇷"),
        VpnLocation("ae-dxb", "ОАЭ", "Дубай", "🇦🇪"),
        VpnLocation("sg-sin", "Сингапур", "Сингапур · Восточная Азия", "🇸🇬"),
        VpnLocation("de-fra", "Германия", "Франкфурт", "🇩🇪"),
        VpnLocation("fr-par", "Франция", "Париж", "🇫🇷"),
        VpnLocation("us-nyc", "США", "Нью-Йорк", "🇺🇸"),
        VpnLocation("us-lax", "США", "Лос-Анджелес", "🇺🇸"),
        VpnLocation("nl-ams", "Нидерланды", "Амстердам", "🇳🇱"),
        VpnLocation("gb-lon", "Великобритания", "Лондон", "🇬🇧"),
    )

    fun byId(id: String?): VpnLocation? = all.firstOrNull { it.id == id }
}
