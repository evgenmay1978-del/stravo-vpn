package com.stravo.vpn

import android.app.Application
import com.stravo.vpn.data.AppContainer

class StravoApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        com.stravo.vpn.data.subscription.SubscriptionRefreshJob.schedule(this)
    }
}
