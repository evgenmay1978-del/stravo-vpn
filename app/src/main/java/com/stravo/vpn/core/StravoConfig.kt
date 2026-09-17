package com.stravo.vpn.core

import com.stravo.vpn.domain.model.FormFactor

/**
 * Единственное место, где живёт конфигурация бота и версии.
 * Username бота меняется здесь одной строкой и больше нигде не дублируется.
 */
object StravoConfig {

    const val BOT_USERNAME: String = "MaestroSecureVPN_bot"

    const val START_PARAM_MOBILE: String = "stravo_quick_connect"
    const val START_PARAM_TV: String = "stravo_tv_quick_connect"
    const val START_PARAM_PAIR_PREFIX: String = "pair_"

    /** Список протоколов показывается информационно, выбор делает сервер подписки. */
    val PROTOCOLS: List<String> = listOf("Авто", "VLESS", "Hysteria2", "AnyTLS", "WebRTC")

    fun startParamFor(formFactor: FormFactor): String =
        if (formFactor == FormFactor.TV) START_PARAM_TV else START_PARAM_MOBILE
}
