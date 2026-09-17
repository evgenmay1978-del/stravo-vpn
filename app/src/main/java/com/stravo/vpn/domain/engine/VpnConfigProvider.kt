package com.stravo.vpn.domain.engine

/**
 * Источник конфига узла для ядра туннеля.
 *
 * Единственное место, где секрет подписки покидает защищённое хранилище — и только
 * по обезличенному идентификатору узла. В UI-модели и в логи конфиг не попадает.
 */
fun interface VpnConfigProvider {
    fun configFor(nodeId: String): String?
}

