package com.stravo.vpn.platform

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.stravo.vpn.domain.model.FormFactor

fun Context.detectFormFactor(): FormFactor {
    val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    val isTelevision = uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    return if (isTelevision) FormFactor.Tv else FormFactor.Phone
}
