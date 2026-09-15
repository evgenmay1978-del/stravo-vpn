package com.stravo.vpn

import android.app.Application
import com.stravo.vpn.data.AppContainer

class StravoApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
