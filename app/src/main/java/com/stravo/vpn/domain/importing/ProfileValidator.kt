package com.stravo.vpn.domain.importing

import android.net.Uri
import com.stravo.vpn.domain.model.EndpointStatus
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.domain.model.ProfileId
import com.stravo.vpn.domain.model.ProtectedProfilePayload
import com.stravo.vpn.domain.model.ServerEndpoint
import com.stravo.vpn.domain.model.VpnMode
import com.stravo.vpn.domain.model.VpnProfile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

sealed interface ValidationResult {
    data class Valid(val profile: VpnProfile) : ValidationResult
    data class Invalid(val error: ImportError) : ValidationResult
}

class ProfileValidator {
    private val shareSchemes = setOf(
        "vless",
        "vmess",
        "trojan",
        "ss",
        "hysteria2",
        "socks5",
        "wireguard",
    )

    fun validate(sourceText: String, formFactor: FormFactor): ValidationResult {
        val value = sourceText.trim()
        if (value.isEmpty()) return ValidationResult.Invalid(ImportError.EmptyInput)

        val mode = detectMode(value)
        if (!mode.isAllowedOn(formFactor)) {
            return ValidationResult.Invalid(ImportError.ModeUnavailable(formFactor, mode))
        }

        val source = classify(value)
            ?: return ValidationResult.Invalid(ImportError.InvalidProfile)
        val endpoint = when (source) {
            is ClassifiedSource.Url -> endpointFromUrl(source.uri)
            is ClassifiedSource.ShareLink -> endpointFromShareLink(source.uri)
            ClassifiedSource.Json -> endpointFromJson(value)
        } ?: return ValidationResult.Invalid(ImportError.InvalidProfile)

        val id = ProfileId(sha256(value).take(24))
        val profile = VpnProfile(
            id = id,
            displayName = endpoint.label,
            mode = mode,
            endpoints = listOf(endpoint.copy(id = id.value)),
            protectedPayload = ProtectedProfilePayload(value.toByteArray(StandardCharsets.UTF_8)),
        )
        return ValidationResult.Valid(profile)
    }

    private fun classify(value: String): ClassifiedSource? {
        if (value.startsWith("{") || value.startsWith("[")) {
            val isJson = runCatching {
                if (value.startsWith("{")) JSONObject(value).length() > 0 else JSONArray(value).length() > 0
            }.getOrDefault(false)
            if (!isJson) return null
            return ClassifiedSource.Json
        }

        val uri = Uri.parse(value)
        return when {
            uri.scheme.orEmpty().lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() ->
                ClassifiedSource.Url(uri)
            uri.scheme.orEmpty().lowercase() in shareSchemes -> ClassifiedSource.ShareLink(uri)
            else -> null
        }
    }

    private fun detectMode(value: String): VpnMode {
        val normalized = value.lowercase().filterNot(Char::isWhitespace)
        return if (
            normalized.contains("\"mode\":\"white_list\"") ||
            normalized.contains("\"mode\":\"whitelist\"") ||
            normalized.contains("\"mode\":\"white-list\"")
        ) {
            VpnMode.WhiteList
        } else {
            VpnMode.Ordinary
        }
    }

    private fun endpointFromUrl(uri: Uri): ServerEndpoint = ServerEndpoint(
        id = uri.host.orEmpty(),
        label = uri.host.orEmpty(),
        countryCode = null,
        latencyMs = null,
        status = EndpointStatus.Unknown,
    )

    private fun endpointFromShareLink(uri: Uri): ServerEndpoint {
        val host = uri.host ?: uri.encodedAuthority
            ?.substringAfter('@', missingDelimiterValue = "")
            ?.substringBefore(':')
            .orEmpty()
        return ServerEndpoint(
            id = host.ifBlank { "imported-server" },
            label = host.ifBlank { "Импортированный сервер" },
            countryCode = null,
            latencyMs = null,
            status = EndpointStatus.Unknown,
        )
    }

    private fun endpointFromJson(value: String): ServerEndpoint? {
        val root = runCatching {
            if (value.startsWith("{")) JSONObject(value) else JSONObject().put("items", JSONArray(value))
        }.getOrNull() ?: return null
        val label = root.optString("remark").ifBlank { "Импортированный профиль" }
        return ServerEndpoint(
            id = "json-profile",
            label = label,
            countryCode = null,
            latencyMs = null,
            status = EndpointStatus.Unknown,
        )
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private sealed interface ClassifiedSource {
        data class Url(val uri: Uri) : ClassifiedSource
        data class ShareLink(val uri: Uri) : ClassifiedSource
        data object Json : ClassifiedSource
    }

}
