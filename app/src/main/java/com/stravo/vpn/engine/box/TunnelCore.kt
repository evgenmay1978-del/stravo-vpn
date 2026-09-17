package com.stravo.vpn.engine.box

import io.nekohasekai.libbox.Libbox

/**
 * Проверка, что нативная часть ядра действительно загрузилась.
 *
 * Java-классы libbox есть всегда (они в AAR), а вот .so-библиотеки — только под
 * arm64-v8a и armeabi-v7a. Если загрузка не удалась, приложение должно честно
 * сказать об этом, а не падать.
 */
object TunnelCore {

    val isAvailable: Boolean by lazy {
        try {
            Libbox.version()
            true
        } catch (error: Throwable) {
            false
        }
    }
}

