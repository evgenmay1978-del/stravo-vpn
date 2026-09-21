package com.stravo.vpn.platform

import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import com.stravo.vpn.domain.model.FormFactor

object DeviceType {

    /** System TV capabilities also cover boxes whose launcher reports a normal uiMode. */
    fun formFactorOf(context: Context): FormFactor {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        val television = uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature("android.hardware.type.television")
        return if (television) FormFactor.TV else FormFactor.PHONE
    }
}
