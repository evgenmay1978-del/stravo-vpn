package com.stravo.vpn.data.subscription

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stravo.vpn.domain.model.ProfileId
import com.stravo.vpn.domain.model.Subscription
import com.stravo.vpn.domain.model.SubscriptionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

interface SubscriptionRepository {
    val subscriptions: Flow<List<Subscription>>
    suspend fun replace(subscription: Subscription)
    suspend fun remove(subscriptionId: String)
}

private val Context.stravoSubscriptionsDataStore by preferencesDataStore(name = "stravo_subscriptions")

class LocalSubscriptionRepository(context: Context) : SubscriptionRepository {
    private val dataStore = context.applicationContext.stravoSubscriptionsDataStore
    private val dataKey = stringPreferencesKey("subscriptions_json")

    override val subscriptions: Flow<List<Subscription>> = dataStore.data.map { preferences ->
        decode(preferences[dataKey])
    }

    override suspend fun replace(subscription: Subscription) {
        dataStore.edit { preferences ->
            val subscriptions = decode(preferences[dataKey])
                .filterNot { it.id == subscription.id } + subscription
            preferences[dataKey] = encode(subscriptions)
        }
    }

    override suspend fun remove(subscriptionId: String) {
        dataStore.edit { preferences ->
            preferences[dataKey] = encode(decode(preferences[dataKey]).filterNot { it.id.value == subscriptionId })
        }
    }

    private fun encode(subscriptions: List<Subscription>): String =
        JSONArray().also { array ->
            subscriptions.forEach { subscription ->
                array.put(
                    JSONObject()
                        .put("id", subscription.id.value)
                        .put("displayName", subscription.displayName)
                        .put("profileIds", JSONArray(subscription.profileIds.map { it.value }))
                        .put("refreshIntervalMinutes", subscription.refreshIntervalMinutes ?: JSONObject.NULL)
                        .put("expiresAtEpochSeconds", subscription.expiresAtEpochSeconds ?: JSONObject.NULL),
                )
            }
        }.toString()

    private fun decode(value: String?): List<Subscription> {
        if (value.isNullOrBlank()) return emptyList()
        val array = JSONArray(value)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)
                val profileIds = json.optJSONArray("profileIds") ?: JSONArray()
                add(
                    Subscription(
                        id = SubscriptionId(json.getString("id")),
                        displayName = json.getString("displayName"),
                        profileIds = buildList(profileIds.length()) {
                            for (profileIndex in 0 until profileIds.length()) {
                                add(ProfileId(profileIds.getString(profileIndex)))
                            }
                        },
                        refreshIntervalMinutes = json.optInt("refreshIntervalMinutes", -1).takeUnless { it < 0 },
                        expiresAtEpochSeconds = json.optLong("expiresAtEpochSeconds", -1L).takeUnless { it < 0L },
                    ),
                )
            }
        }
    }
}
