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
) {
    val modes: List<NetworkMode> get() = CapabilityPolicy.availableModes(formFactor)

    val isTv: Boolean get() = formFactor.isTv

    /**
     * Локации для выбора. Пока подписки нет — витрина каталога; после импорта
     * показываются реальные серверы подписки (только страна, город и протокол).
     */
    val locations: List<VpnLocation> = if (subscriptionNodes.isEmpty()) {
        LocationsCatalog.all
    } else {
        listOf(LocationsCatalog.AUTO) + SubscriptionLocations.from(subscriptionNodes)
    }

    fun nodeFor(locationId: String): SubscriptionNode? =
        subscriptionNodes.firstOrNull { it.id == locationId }
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
    data class LocationSelected(val locationId: String) : HomeEvent
    data class ModeSelected(val mode: NetworkMode) : HomeEvent
    data class NoticeShown(val notice: Notice) : HomeEvent
    data object NoticeConsumed : HomeEvent
    data class SubscriptionSubmitted(val raw: String) : HomeEvent
    data object SubscriptionImportCleared : HomeEvent
    data object SubscriptionRemoved : HomeEvent
}

