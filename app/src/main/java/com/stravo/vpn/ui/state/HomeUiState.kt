package com.stravo.vpn.ui.state

import com.stravo.vpn.data.subscription.ImportError
import com.stravo.vpn.domain.model.ConnectionState
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.domain.model.LocationsCatalog
import com.stravo.vpn.domain.model.NetworkMode
import com.stravo.vpn.domain.model.Subscription
import com.stravo.vpn.domain.model.VpnLocation
import com.stravo.vpn.domain.model.VpnProfile
import com.stravo.vpn.domain.model.VpnStats
import com.stravo.vpn.domain.policy.CapabilityPolicy
import com.stravo.vpn.domain.subscription.SubscriptionLocations
import com.stravo.vpn.domain.subscription.SubscriptionNode
import com.stravo.vpn.domain.subscription.Unrecognized
import com.stravo.vpn.domain.subscription.SubscriptionService

/** Состояние добавления подписки. */
sealed interface SubscriptionImportState {
    data object Idle : SubscriptionImportState
    data object Loading : SubscriptionImportState
    data class Failed(
        val error: ImportError,
        val reason: Unrecognized? = null,
        val token: String? = null,
    ) : SubscriptionImportState
    data class Done(val nodeCount: Int) : SubscriptionImportState
}

/** Одно неизменяемое состояние главного экрана. */
data class HomeUiState(
    val formFactor: FormFactor = FormFactor.PHONE,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val location: VpnLocation = LocationsCatalog.AUTO,
    val profile: VpnProfile? = null,
    val subscription: Subscription = Subscription.None,
    val subscriptionNodes: List<SubscriptionNode> = emptyList(),
    val mode: NetworkMode = NetworkMode.NORMAL_VPN,
    val stats: VpnStats = VpnStats.Empty,
    val notice: Notice? = null,
    val importState: SubscriptionImportState = SubscriptionImportState.Idle,
    /** Одна строка о нештатном прошлом запуске (сбой ядра/приложения). Показывается один раз. */
    val diagnostics: String? = null,
    /** Последние строки журнала ядра: только шаги и сообщения без адресов и ключей. */
    val coreLog: List<String> = emptyList(),
    /** Идёт самопроверка туннеля — кнопка показывает занятость. */
    val probing: Boolean = false,
    /**
     * Локация, которая реально поднята в туннеле. Может не совпадать с выбранной:
     * смена локации на ходу не перезапускает ядро, и карточка не должна врать.
     */
    val connectedLocationId: String? = null,
) {
    /** Локация поднятого туннеля (если он поднят). */
    val tunnelLocation: VpnLocation?
        get() = connectedLocationId?.let { id -> locations.firstOrNull { it.id == id } }

    val modes: List<NetworkMode> get() = CapabilityPolicy.availableModes(formFactor)

    val isTv: Boolean get() = formFactor.isTv

    val availableNodes: List<SubscriptionNode> get() = subscriptionNodes.filter {
        CapabilityPolicy.permits(it.service, formFactor) && it.service ==
            (if (mode == NetworkMode.FREE_INTERNET) SubscriptionService.CDN else SubscriptionService.ORDINARY)
    }

    /**
     * Локации для выбора. Пока подписки нет — витрина каталога; после импорта
     * показываются реальные серверы подписки (только страна, город и протокол).
     */
    val locations: List<VpnLocation> = if (subscriptionNodes.isEmpty()) {
        LocationsCatalog.all
    } else {
        listOf(LocationsCatalog.AUTO) + SubscriptionLocations.from(availableNodes)
    }

    fun nodeFor(locationId: String): SubscriptionNode? =
        availableNodes.firstOrNull { it.id == locationId }

    /**
     * Автоматический сервер: у пункта «Авто» своего узла нет — берём первый из подписки.
     * Без этого «Подключить» на авто-локации честно отвечало «нет ключа узла».
     */
    fun nodeForLocation(): SubscriptionNode? =
        if (location.id == LocationsCatalog.AUTO.id) {
            availableNodes.firstOrNull()
        } else {
            nodeFor(location.id)
        }

    /** Локация, к которой привязан узел: для авто — первая из подписки. */
    fun connectedLocation(node: SubscriptionNode?): VpnLocation =
        if (location.id == LocationsCatalog.AUTO.id && node != null) {
            locations.firstOrNull { it.id == node.id } ?: location
        } else {
            location
        }
}

enum class Notice {
    LINK_COPIED,
    BOT_UNAVAILABLE,
    CORE_MISSING,
    PAIRING_BACKEND_MISSING,
    PROFILE_MISSING,
    SCAN_INVALID,
    SUBSCRIPTION_ADDED,
    SUBSCRIPTION_REMOVED,
    SUBSCRIPTION_REQUIRED,
}

/** Все побочные эффекты идут событиями — Compose не дёргает сервисы напрямую. */
sealed interface HomeEvent {
    data object PowerClick : HomeEvent
    data object QuickConnectClick : HomeEvent
    data object Retry : HomeEvent
    data object ProbeClick : HomeEvent
    data class LocationSelected(val locationId: String) : HomeEvent
    data class ModeSelected(val mode: NetworkMode) : HomeEvent
    data class NoticeShown(val notice: Notice) : HomeEvent
    data object NoticeConsumed : HomeEvent
    data class SubscriptionSubmitted(val raw: String) : HomeEvent
    data class LoginSubmitted(val login: String) : HomeEvent
    data object SubscriptionImportCleared : HomeEvent
    data object SubscriptionRemoved : HomeEvent
}

