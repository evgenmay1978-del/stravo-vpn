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
import com.stravo.vpn.engine.box.SingBoxVpnEngine
import com.stravo.vpn.engine.box.TunnelCore
import com.stravo.vpn.pairing.PairingRepository

/** Ручная DI-обвязка: один контейнер на приложение, без магии. */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val settings: SettingsRepository = SettingsRepository(appContext)

    val profiles: ProfileRepository = ProfileRepository()

    val subscriptions: SubscriptionRepository = SubscriptionRepository()

    /** Полные конфиги узлов подписки: только Keystore, только по запросу ядра. */
    val secretStore: SecretStore = SecretStore(appContext)

    val subscriptionImporter: SubscriptionImporter = SubscriptionImporter(secretStore)

    val pairing: PairingRepository = PairingRepository()

    private val configProvider: VpnConfigProvider =
        VpnConfigProvider { nodeId -> subscriptionImporter.configFor(nodeId) }

    /**
     * Ядро — sing-box (libbox), см. docs/IMPLEMENTATION.md, разделы 1b–1d.
     * Если нативная часть не загрузилась (чужой ABI, обрезанный APK), приложение
     * честно сообщает об этом и не рисует «подключено».
     */
    val vpnEngine: VpnEngine = if (TunnelCore.isAvailable) {
        SingBoxVpnEngine(context = appContext, configProvider = configProvider)
    } else {
        UnavailableVpnEngine(
            configProvider = configProvider,
            reason = UnavailableVpnEngine.NATIVE_FAILED_REASON,
        )
    }
}

