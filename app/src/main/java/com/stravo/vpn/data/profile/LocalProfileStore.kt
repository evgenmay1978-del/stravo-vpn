package com.stravo.vpn.data.profile

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.stravo.vpn.domain.model.EndpointStatus
import com.stravo.vpn.domain.model.ProfileId
import com.stravo.vpn.domain.model.ServerEndpoint
import com.stravo.vpn.domain.model.VpnMode
import com.stravo.vpn.domain.model.VpnProfile
import com.stravo.vpn.domain.model.ProtectedProfilePayload
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class StoredProfileRecord(
    val profile: VpnProfile,
    val protectedPayload: ByteArray,
)

class LocalProfileStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "stravo-profiles.json"))

    @Synchronized
    fun read(): List<StoredProfileRecord> {
        if (!file.baseFile.exists()) return emptyList()
        val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        require(root.optInt("version", -1) == 1) { "Unsupported STRAVO profile store version" }
        val profiles = root.optJSONArray("profiles") ?: JSONArray()
        return buildList(profiles.length()) {
            for (index in 0 until profiles.length()) {
                add(readRecord(profiles.getJSONObject(index)))
            }
        }
    }

    @Synchronized
    fun replace(records: List<StoredProfileRecord>) {
        val root = JSONObject()
            .put("version", 1)
            .put("profiles", JSONArray().also { array -> records.forEach { array.put(writeRecord(it)) } })
        val output = file.startWrite()
        try {
            output.bufferedWriter().use { it.write(root.toString()) }
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun readRecord(json: JSONObject): StoredProfileRecord {
        val endpoints = json.optJSONArray("endpoints") ?: JSONArray()
        val profile = VpnProfile(
            id = ProfileId(json.getString("id")),
            displayName = json.getString("displayName"),
            mode = VpnMode.entries.first { it.wireName == json.getString("mode") },
            endpoints = buildList(endpoints.length()) {
                for (index in 0 until endpoints.length()) {
                    val endpoint = endpoints.getJSONObject(index)
                    add(
                        ServerEndpoint(
                            id = endpoint.getString("id"),
                            label = endpoint.getString("label"),
                            countryCode = endpoint.optString("countryCode").ifBlank { null },
                            latencyMs = endpoint.optInt("latencyMs", -1).takeUnless { it < 0 },
                            status = EndpointStatus.entries.first { it.wireName == endpoint.getString("status") },
                        ),
                    )
                }
            },
            protectedPayload = ProtectedProfilePayload(ByteArray(0)),
        )
        val payload = Base64.decode(json.getString("payload"), Base64.NO_WRAP)
        return StoredProfileRecord(profile, payload)
    }

    private fun writeRecord(record: StoredProfileRecord): JSONObject {
        val profile = record.profile
        val endpoints = JSONArray().also { array ->
            profile.endpoints.forEach { endpoint ->
                array.put(
                    JSONObject()
                        .put("id", endpoint.id)
                        .put("label", endpoint.label)
                        .put("countryCode", endpoint.countryCode ?: JSONObject.NULL)
                        .put("latencyMs", endpoint.latencyMs ?: JSONObject.NULL)
                        .put("status", endpoint.status.wireName),
                )
            }
        }
        return JSONObject()
            .put("id", profile.id.value)
            .put("displayName", profile.displayName)
            .put("mode", profile.mode.wireName)
            .put("endpoints", endpoints)
            .put("payload", Base64.encodeToString(record.protectedPayload, Base64.NO_WRAP))
    }
}
