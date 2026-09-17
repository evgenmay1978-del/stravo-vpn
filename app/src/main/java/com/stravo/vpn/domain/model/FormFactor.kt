package com.stravo.vpn.domain.model

/** Одно приложение — два форм-фактора. */
enum class FormFactor {
    PHONE,
    TV,
    ;

    val isTv: Boolean get() = this == TV
    val isPhone: Boolean get() = this == PHONE
}
