package com.stravo.vpn.domain.model

enum class VpnConnectionState(val wireName: String) {
    Idle("idle"),
    Preparing("preparing"),
    Connected("connected"),
    Disconnecting("disconnecting"),
    Error("error"),
}
