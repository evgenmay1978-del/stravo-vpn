package com.stravo.vpn.engine.box

import android.content.Context

/**
 * Вариант сборки конфига ядра — для диагностики на живом устройстве.
 *
 * Когда туннель поднимается, а трафик не идёт, причину ищут перебором: DNS и
 * сетевой стек. Переключение варианта не требует новой сборки APK: выбранное
 * значение хранится в настройках и читается сервисом при следующем подключении.
 */
enum class CoreVariant(val label: String, val hint: String) {
    BASE("1/3 · как есть", "hijack DNS и стек mixed"),
    SYSTEM_STACK("2/3 · системный стек", "stack system: другой путь обработки пакетов TUN"),
    DIRECT_RESOLVER("3/3 · DNS напрямую", "DNS уходит в сеть оператора, а не в узел"),
}

/** Хранилище выбранного варианта: секретов здесь нет. */
class CoreTuning(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun variant(): CoreVariant = runCatching {
        CoreVariant.valueOf(prefs.getString(KEY_VARIANT, null) ?: CoreVariant.BASE.name)
    }.getOrDefault(CoreVariant.BASE)

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
