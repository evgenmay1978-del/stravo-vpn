package com.stravo.vpn.domain.model

/**
 * Метрики соединения. Пока измерений нет — поля null и UI показывает длинное тире,
 * а не выдуманные числа.
 */
data class VpnStats(
    val pingMs: Int? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
) {
    companion object {
        const val PLACEHOLDER: String = "—"
        val Empty: VpnStats = VpnStats()
    }
}

fun Int?.asPing(): String = this?.toString() ?: VpnStats.PLACEHOLDER

fun Double?.asSpeed(): String =
    if (this == null) VpnStats.PLACEHOLDER else String.format(java.util.Locale.US, "%.1f", this)
