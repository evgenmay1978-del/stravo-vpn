package com.stravo.vpn.data

import android.content.Context
import com.stravo.vpn.data.profile.ProfileRepository
import com.stravo.vpn.data.settings.SettingsRepository
import com.stravo.vpn.data.subscription.SubscriptionRepository
import com.stravo.vpn.domain.engine.UnavailableVpnEngine
import com.stravo.vpn.domain.engine.VpnEngine
import com.stravo.vpn.pairing.PairingRepository

/** Ручная DI-обвязка: один контейнер на приложение, без магии. */
class AppContainer(context: Context) {

    val settings: SettingsRepository = SettingsRepository(context)

    val profiles: ProfileRepository = ProfileRepository()

    val subscriptions: SubscriptionRepository = SubscriptionRepository()

    val pairing: PairingRepository = PairingRepository()

    /** Настоящего ядра пока нет — см. docs/IMPLEMENTATION.md. */
    val vpnEngine: VpnEngine = UnavailableVpnEngine()
}
