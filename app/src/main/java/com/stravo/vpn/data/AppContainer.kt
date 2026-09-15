package com.stravo.vpn.data

import android.content.Context
import com.stravo.vpn.data.profile.LocalProfileRepository
import com.stravo.vpn.data.profile.LocalProfileStore
import com.stravo.vpn.data.security.AndroidSecretStore
import com.stravo.vpn.data.settings.DataStoreSettingsRepository
import com.stravo.vpn.data.settings.SettingsRepository
import com.stravo.vpn.data.subscription.LocalSubscriptionRepository
import com.stravo.vpn.data.subscription.SubscriptionRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val secretStore = AndroidSecretStore()

    val profileRepository = LocalProfileRepository(
        store = LocalProfileStore(appContext),
        secretStore = secretStore,
    )
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(appContext)
    val subscriptionRepository: SubscriptionRepository = LocalSubscriptionRepository(appContext)
}
