package com.stravo.vpn.data

import android.content.Context
import com.stravo.vpn.data.profile.ProfileRepository
import com.stravo.vpn.data.secret.SecretStore
import com.stravo.vpn.data.settings.SettingsRepository
import com.stravo.vpn.data.subscription.SubscriptionImporter
import com.stravo.vpn.data.subscription.SubscriptionRepository
import com.stravo.vpn.domain.engine.UnavailableVpnEngine
import com.stravo.vpn.domain.engine.VpnConfigProvider
import com.stravo.vpn.domain.engine.VpnEngine
import com.stravo.vpn.pairing.PairingRepository

/** Ручная DI-обвязка: один контейнер на приложение, без магии. */
class AppContainer(context: Context) {

    val settings: SettingsRepository = SettingsRepository(context)

    val profiles: ProfileRepository = ProfileRepository()

    val subscriptions: SubscriptionRepository = SubscriptionRepository()

    /** Полные конфиги узлов подписки: только Keystore, только по запросу ядра. */
    val secretStore: SecretStore = SecretStore(context)

    val subscriptionImporter: SubscriptionImporter = SubscriptionImporter(secretStore)

    val pairing: PairingRepository = PairingRepository()

    /**
     * Настоящего ядра пока нет — см. docs/IMPLEMENTATION.md. Контракт уже полный:
     * ядро получает профиль и может забрать конфиг узла через [VpnConfigProvider].
     */
    val vpnEngine: VpnEngine = UnavailableVpnEngine(
        configProvider = VpnConfigProvider { nodeId -> subscriptionImporter.configFor(nodeId) },
    )
}

