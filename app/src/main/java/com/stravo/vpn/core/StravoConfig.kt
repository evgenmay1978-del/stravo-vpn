package com.stravo.vpn.core

import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.domain.model.ProtocolCatalog

/**
 * Единственное место, где живёт конфигурация бота и версии.
 * Username бота меняется здесь одной строкой и больше нигде не дублируется.
 */
object StravoConfig {

    const val BOT_USERNAME: String = "MaestroSecureVPN_bot"

    const val START_PARAM_MOBILE: String = "stravo_quick_connect"
    const val START_PARAM_TV: String = "stravo_tv_quick_connect"
    const val START_PARAM_PAIR_PREFIX: String = "pair_"

    /**
     * Список протоколов информационный: транспорт выбирает конфиг узла из подписки.
     * Значения берутся из каталога, чтобы настройки и разбор ссылок не разъезжались.
     */
    val PROTOCOLS: List<String> = ProtocolCatalog.settingsLabels

    /** Полоса протоколов на TV: короткие подписи, без переносов. */
    val PROTOCOL_STRIP: List<String> = ProtocolCatalog.stripLabels

    fun startParamFor(formFactor: FormFactor): String =
        if (formFactor == FormFactor.TV) START_PARAM_TV else START_PARAM_MOBILE
}
