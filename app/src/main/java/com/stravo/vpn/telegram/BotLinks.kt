package com.stravo.vpn.telegram

import com.stravo.vpn.core.StravoConfig
import com.stravo.vpn.domain.model.FormFactor

/**
 * Чистый построитель ссылок: без Android-зависимостей, поэтому легко проверяется.
 * Никаких секретов в ссылках быстрого подключения нет.
 */
object BotLinks {

    const val HTTPS_BASE: String = "https://t.me/"

    fun httpsStart(startParam: String): String =
        HTTPS_BASE + StravoConfig.BOT_USERNAME + "?start=" + startParam

    fun telegramAppStart(startParam: String): String =
        "tg://resolve?domain=" + StravoConfig.BOT_USERNAME + "&start=" + startParam

    fun quickConnectStartParam(formFactor: FormFactor): String =
        StravoConfig.startParamFor(formFactor)

    fun quickConnectHttps(formFactor: FormFactor): String =
        httpsStart(quickConnectStartParam(formFactor))

    fun quickConnectInApp(formFactor: FormFactor): String =
        telegramAppStart(quickConnectStartParam(formFactor))

    fun pairingStartParam(token: String): String =
        StravoConfig.START_PARAM_PAIR_PREFIX + token

    fun pairingHttps(token: String): String = httpsStart(pairingStartParam(token))

    fun supportHttps(): String = HTTPS_BASE + StravoConfig.BOT_USERNAME

    fun supportHandle(): String = "@" + StravoConfig.BOT_USERNAME
}
