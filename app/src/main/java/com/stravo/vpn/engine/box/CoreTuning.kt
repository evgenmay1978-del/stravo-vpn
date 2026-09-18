package com.stravo.vpn.engine.box

import android.content.Context

/**
 * Вариант сборки конфига ядра — для диагностики на живом устройстве.
 *
 * Когда туннель поднимается, а трафик не идёт, причину ищут перебором: сетевой стек
 * и DNS. Переключение варианта не требует новой сборки APK: выбранное значение
 * хранится в настройках и читается сервисом при следующем подключении.
 *
 * По умолчанию — gVisor: в стеке `mixed`/`system` TCP идёт через системный стек
 * (NAT + возврат пакета в TUN), и на этом устройстве соединения до обработчика
 * не доходят вовсе — в журнале видны только `pre-match[0] => sniff` от SYN и ни
 * одной строки `inbound connection from`. Клиенты на Xray (INCY, Happ, v2rayNG)
 * работают через gVisor.
 */
enum class CoreVariant(val label: String, val hint: String) {
    GVISOR("1/5 · gVisor", "TCP и UDP через gVisor — так работают клиенты на Xray"),
    MIXED("2/5 · mixed", "TCP через системный стек, UDP через gVisor; было по умолчанию"),
    SYSTEM_STACK("3/5 · системный стек", "весь трафик через системный стек"),
    DIRECT_RESOLVER("4/5 · DNS напрямую", "DNS уходит в сеть оператора, трафик — в узел"),
    LOCAL_PROXY("5/5 · локальный прокси", "SOCKS на 127.0.0.1:10808, без TUN: проверка узла"),
}

/** Хранилище выбранного варианта: секретов здесь нет. */
class CoreTuning(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun variant(): CoreVariant = runCatching {
        CoreVariant.valueOf(prefs.getString(KEY_VARIANT, null) ?: CoreVariant.GVISOR.name)
    }.getOrDefault(CoreVariant.GVISOR)

    fun setVariant(variant: CoreVariant) {
        prefs.edit().putString(KEY_VARIANT, variant.name).commit()
    }

    /** Следующий вариант по кругу — переключатель в настройках диагностики. */
    fun next(): CoreVariant {
        val values = CoreVariant.entries
        val next = values[(variant().ordinal + 1) % values.size]
        setVariant(next)
        return next
    }

    private companion object {
        const val PREFS = "stravo.core.tuning"
        const val KEY_VARIANT = "variant"
    }
}
