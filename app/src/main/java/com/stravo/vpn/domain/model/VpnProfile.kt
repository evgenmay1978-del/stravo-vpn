package com.stravo.vpn.domain.model

@JvmInline
value class ProfileId(val value: String)

data class ProtectedProfilePayload(val bytes: ByteArray)

data class VpnProfile(
    val id: ProfileId,
    val displayName: String,
    val mode: VpnMode,
    val endpoints: List<ServerEndpoint>,
    val protectedPayload: ProtectedProfilePayload,
) {
    fun summary(): VpnProfileSummary = VpnProfileSummary(
        id = id,
        displayName = displayName,
        mode = mode,
        endpointCount = endpoints.size,
    )
}

data class VpnProfileSummary(
    val id: ProfileId,
    val displayName: String,
    val mode: VpnMode,
    val endpointCount: Int,
)
