package com.stravo.vpn.domain.model

/**
 * Режимы работы.
 *
 * NORMAL_VPN доступен везде.
 * FREE_INTERNET — только на телефоне; на TV режим недоступен и не показывается
 * (см. CapabilityPolicy, fail-closed).
 */
enum class NetworkMode {
    NORMAL_VPN,
    FREE_INTERNET,
}
