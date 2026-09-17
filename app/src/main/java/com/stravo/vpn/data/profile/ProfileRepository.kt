package com.stravo.vpn.data.profile

import com.stravo.vpn.domain.model.VpnProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Профили без секретов. Полный конфиг хранится вне репозитория и в логи не попадает.
 */
class ProfileRepository {

    private val _profiles = MutableStateFlow<List<VpnProfile>>(emptyList())
    private val _selectedId = MutableStateFlow<String?>(null)

    val profiles: StateFlow<List<VpnProfile>> = _profiles.asStateFlow()
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    fun replaceAll(profiles: List<VpnProfile>) {
        _profiles.value = profiles
        if (_selectedId.value == null) _selectedId.value = profiles.firstOrNull()?.id
    }

    fun select(id: String?) {
        _selectedId.value = id
    }

    fun selected(): VpnProfile? = _profiles.value.firstOrNull { it.id == _selectedId.value }
}
