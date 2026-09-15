package com.stravo.vpn.domain.importing

import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.domain.model.VpnMode
import com.stravo.vpn.domain.model.VpnProfileSummary

sealed interface ImportResult {
    data class Success(val summary: VpnProfileSummary) : ImportResult
    data class Failure(val error: ImportError) : ImportResult
}

sealed class ImportError(
    val code: String,
    val userMessage: String,
) {
    data object EmptyInput : ImportError("empty_input", "Добавьте ссылку или конфигурацию")
    data object InvalidProfile : ImportError("invalid_profile", "Профиль не распознан")
    data class UnsupportedFormat(val source: String) : ImportError(
        "unsupported_format",
        "Формат $source пока не поддерживается",
    )
    data class ModeUnavailable(
        val formFactor: FormFactor,
        val mode: VpnMode,
    ) : ImportError(
        "mode_unavailable",
        "Режим недоступен на этом устройстве",
    )
    data object StorageFailure : ImportError("storage_failure", "Профиль не удалось сохранить")
}
