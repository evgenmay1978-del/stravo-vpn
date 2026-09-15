package com.stravo.vpn.ui.screens.importing

import com.stravo.vpn.domain.importing.ImportResult
import com.stravo.vpn.domain.importing.ProfileImportSource
import com.stravo.vpn.domain.importing.ProfileImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ImportUiState(
    val input: String = "",
    val isBusy: Boolean = false,
    val result: ImportResult? = null,
)

class ImportViewModel(private val importer: ProfileImporter) {
    private val mutableState = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = mutableState.asStateFlow()

    fun setInput(value: String) {
        mutableState.value = mutableState.value.copy(input = value, result = null)
    }

    fun importProfile(source: ProfileImportSource, scope: CoroutineScope) {
        if (mutableState.value.isBusy) return
        scope.launch {
            mutableState.value = mutableState.value.copy(isBusy = true, result = null)
            val result = importer.importProfile(source)
            mutableState.value = mutableState.value.copy(isBusy = false, result = result)
        }
    }
}
