package com.stravo.vpn.data.subscription

import com.stravo.vpn.domain.model.Subscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Репозиторий подписки.
 *
 * Хранит только безопасные метаданные (план, дата окончания, флаг активности).
 * Импорт настоящей подписки появится вместе с pairing-API сервиса; до этого
 * репозиторий не выдумывает «успешный» импорт.
 */
class SubscriptionRepository {

    private val _subscription = MutableStateFlow(Subscription.None)

    val subscription: StateFlow<Subscription> = _subscription.asStateFlow()

    fun setActive(planName: String, activeUntil: String?) {
        _subscription.value = Subscription(planName = planName, activeUntil = activeUntil, isActive = true)
    }

    fun clear() {
        _subscription.value = Subscription.None
    }
}
