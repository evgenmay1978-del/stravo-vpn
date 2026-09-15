package com.stravo.vpn.domain.model

data class ServerEndpoint(
    val id: String,
    val label: String,
    val countryCode: String?,
    val latencyMs: Int?,
    val status: EndpointStatus,
)

enum class EndpointStatus(val wireName: String) {
    Unknown("unknown"),
    Available("available"),
    Unavailable("unavailable"),
}
