package com.stravo.vpn.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class StravoSettings(
    val firstRunComplete: Boolean = false,
    val selectedProfileId: String? = null,
    val selectedModeWireName: String = "ordinary",
    val autoConnect: Boolean = false,
    val reconnectOnNetworkLoss: Boolean = true,
)

interface SettingsRepository {
    val settings: Flow<StravoSettings>
    suspend fun update(transform: (StravoSettings) -> StravoSettings)
}

private val Context.stravoSettingsDataStore by preferencesDataStore(name = "stravo_settings")

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {
    private object Keys {
        val firstRunComplete = booleanPreferencesKey("first_run_complete")
        val selectedProfileId = stringPreferencesKey("selected_profile_id")
        val selectedMode = stringPreferencesKey("selected_mode")
        val autoConnect = booleanPreferencesKey("auto_connect")
        val reconnectOnNetworkLoss = booleanPreferencesKey("reconnect_on_network_loss")
    }

    override val settings: Flow<StravoSettings> = context.stravoSettingsDataStore.data.map { preferences ->
        StravoSettings(
            firstRunComplete = preferences[Keys.firstRunComplete] ?: false,
            selectedProfileId = preferences[Keys.selectedProfileId],
            selectedModeWireName = preferences[Keys.selectedMode] ?: "ordinary",
            autoConnect = preferences[Keys.autoConnect] ?: false,
            reconnectOnNetworkLoss = preferences[Keys.reconnectOnNetworkLoss] ?: true,
        )
    }

    override suspend fun update(transform: (StravoSettings) -> StravoSettings) {
        context.stravoSettingsDataStore.edit { preferences ->
            val current = StravoSettings(
                firstRunComplete = preferences[Keys.firstRunComplete] ?: false,
                selectedProfileId = preferences[Keys.selectedProfileId],
                selectedModeWireName = preferences[Keys.selectedMode] ?: "ordinary",
                autoConnect = preferences[Keys.autoConnect] ?: false,
                reconnectOnNetworkLoss = preferences[Keys.reconnectOnNetworkLoss] ?: true,
            )
            val next = transform(current)
            preferences[Keys.firstRunComplete] = next.firstRunComplete
            preferences[Keys.selectedMode] = next.selectedModeWireName
            preferences[Keys.autoConnect] = next.autoConnect
            preferences[Keys.reconnectOnNetworkLoss] = next.reconnectOnNetworkLoss
            if (next.selectedProfileId == null) {
                preferences.remove(Keys.selectedProfileId)
            } else {
                preferences[Keys.selectedProfileId] = next.selectedProfileId
            }
        }
    }
}
