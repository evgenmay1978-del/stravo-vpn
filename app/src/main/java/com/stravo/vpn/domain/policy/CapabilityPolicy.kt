package com.stravo.vpn.domain.policy

import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.domain.model.VpnMode

data class PlatformCapabilities(
    val formFactor: FormFactor,
    val modes: Set<VpnMode>,
    val supportsRemoteFocus: Boolean,
)

sealed class CapabilityError(message: String) : Exception(message) {
    class ModeUnavailable(
        val formFactor: FormFactor,
        val mode: VpnMode,
    ) : CapabilityError("${mode.wireName} is unavailable on ${formFactor.wireName}")
}

fun capabilitiesFor(formFactor: FormFactor): PlatformCapabilities = when (formFactor) {
    FormFactor.Phone -> PlatformCapabilities(
        formFactor = formFactor,
        modes = setOf(VpnMode.Ordinary, VpnMode.WhiteList),
        supportsRemoteFocus = false,
    )
    FormFactor.Tv -> PlatformCapabilities(
        formFactor = formFactor,
        modes = setOf(VpnMode.Ordinary),
        supportsRemoteFocus = true,
    )
}

fun VpnMode.isAllowedOn(formFactor: FormFactor): Boolean =
    this in capabilitiesFor(formFactor).modes

fun requireAllowedMode(formFactor: FormFactor, mode: VpnMode): Result<Unit> =
    if (mode.isAllowedOn(formFactor)) {
        Result.success(Unit)
    } else {
        Result.failure(CapabilityError.ModeUnavailable(formFactor, mode))
    }
