package com.stravo.vpn.data.subscription

import com.stravo.vpn.domain.model.Subscription
import com.stravo.vpn.domain.subscription.SubscriptionNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Репозиторий подписки.
 *
 * Хранит только безопасные метаданные (план, дата окончания, флаг активности)
 * и безопасные карточки узлов. Полные конфиги лежат в [com.stravo.vpn.data.secret.SecretStore]
 * и сюда не попадают.
 */
class SubscriptionRepository {

    private val _subscription = MutableStateFlow(Subscription.None)
    private val _nodes = MutableStateFlow<List<SubscriptionNode>>(emptyList())

    val subscription: StateFlow<Subscription> = _subscription.asStateFlow()

    /** Узлы текущей подписки: имя, протокол, транспорт. Без хостов и ключей. */
    val nodes: StateFlow<List<SubscriptionNode>> = _nodes.asStateFlow()

    fun setActive(planName: String, activeUntil: String?) {
        _subscription.value = Subscription(planName = planName, activeUntil = activeUntil, isActive = true)
    }

    /** Результат реального импорта: подписка, дата окончания и узлы. */
    fun applyImport(planName: String?, activeUntil: String?, nodes: List<SubscriptionNode>) {
        _nodes.value = nodes
        _subscription.value = Subscription(
            planName = planName?.takeIf { it.isNotBlank() } ?: DEFAULT_PLAN,
            activeUntil = activeUntil,
            isActive = nodes.isNotEmpty(),
        )
    }

    fun clear() {
        _nodes.value = emptyList()
        _subscription.value = Subscription.None
    }

    private companion object {
        const val DEFAULT_PLAN = "STRAVO VPN"
    }
}

