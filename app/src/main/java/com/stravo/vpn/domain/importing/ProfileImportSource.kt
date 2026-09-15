package com.stravo.vpn.domain.importing

sealed interface ProfileImportSource {
    val value: String

    data class Url(override val value: String) : ProfileImportSource
    data class Clipboard(override val value: String) : ProfileImportSource
    data class QrText(override val value: String) : ProfileImportSource
}
