package com.stravo.vpn.data.profile

import com.stravo.vpn.data.security.SecretStore
import com.stravo.vpn.domain.model.ProfileId
import com.stravo.vpn.domain.model.ProtectedProfilePayload
import com.stravo.vpn.domain.model.VpnProfile
import com.stravo.vpn.domain.model.VpnProfileSummary

interface ProfileRepository {
    suspend fun listProfiles(): List<VpnProfileSummary>
    suspend fun save(profile: VpnProfile): ProfileId
    suspend fun delete(id: ProfileId)
    suspend fun get(id: ProfileId): VpnProfile?
}

class LocalProfileRepository internal constructor(
    private val store: LocalProfileStore,
    private val secretStore: SecretStore,
) : ProfileRepository {
    override suspend fun listProfiles(): List<VpnProfileSummary> =
        store.read().map { it.profile.summary() }

    override suspend fun save(profile: VpnProfile): ProfileId {
        val records = store.read().filterNot { it.profile.id == profile.id }.toMutableList()
        records += StoredProfileRecord(
            profile = profile.copy(protectedPayload = ProtectedProfilePayload(ByteArray(0))),
            protectedPayload = secretStore.protect(profile.protectedPayload.bytes),
        )
        store.replace(records)
        return profile.id
    }

    override suspend fun delete(id: ProfileId) {
        store.replace(store.read().filterNot { it.profile.id == id })
    }

    override suspend fun get(id: ProfileId): VpnProfile? {
        val record = store.read().firstOrNull { it.profile.id == id } ?: return null
        return record.profile.copy(
            protectedPayload = ProtectedProfilePayload(secretStore.unprotect(record.protectedPayload)),
        )
    }
}
