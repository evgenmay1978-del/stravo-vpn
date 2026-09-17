package com.stravo.vpn.domain.policy

import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.domain.model.NetworkMode

/**
 * Единственный источник правды о том, что доступно на конкретном форм-факторе.
 * Логика fail-closed: всё, что не разрешено явно, запрещено.
 */
object CapabilityPolicy {

    fun availableModes(formFactor: FormFactor): List<NetworkMode> = when (formFactor) {
        FormFactor.PHONE -> listOf(NetworkMode.NORMAL_VPN, NetworkMode.FREE_INTERNET)
        FormFactor.TV -> listOf(NetworkMode.NORMAL_VPN)
    }

    fun canUseFreeInternet(formFactor: FormFactor): Boolean = formFactor.isPhone

    /** Экран «Подключить ТВ» с инструкцией и сканером есть только на телефоне. */
    fun canScanPairingQr(formFactor: FormFactor): Boolean = formFactor.isPhone

    /** QR для переноса подписки показывает только TV. */
    fun showsPairingQr(formFactor: FormFactor): Boolean = formFactor.isTv

    /** Никаких пользовательских переключателей режима «белых списков» в интерфейсе нет. */
    fun normalizes(requested: NetworkMode, formFactor: FormFactor): NetworkMode =
        if (availableModes(formFactor).contains(requested)) requested else NetworkMode.NORMAL_VPN
}
