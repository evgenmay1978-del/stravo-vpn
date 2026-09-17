package com.stravo.vpn.platform

import android.content.Context
import android.content.res.Configuration
import com.stravo.vpn.domain.model.FormFactor

object DeviceType {

    /** TV определяется через uiMode, без опоры на размер экрана. */
    fun formFactorOf(context: Context): FormFactor {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) FormFactor.TV else FormFactor.PHONE
    }
}
