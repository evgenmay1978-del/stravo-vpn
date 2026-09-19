package com.stravo.vpn.data.subscription

import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.stravo.vpn.core.StravoConfig
import com.stravo.vpn.platform.DeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Existing Maestro /claim contract. Login and returned bearer URL are never logged. */
class SubscriptionAccountAccess(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("stravo.device", Context.MODE_PRIVATE)
    private val deviceId: String = prefs.getString("id", null) ?: run {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val identity = androidId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        MessageDigest.getInstance("SHA-256").digest((appContext.packageName + ":" + identity).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
            .also { prefs.edit().putString("id", it).commit() }
    }

    suspend fun claim(login: String, importer: SubscriptionImporter): ImportOutcome = withContext(Dispatchers.IO) {
        val code = login.trim()
        if (!Regex("[A-Za-z0-9._@-]{1,64}").matches(code)) {
            return@withContext ImportOutcome.Failure(ImportError.LOGIN_REJECTED)
        }
        var connection: HttpURLConnection? = null
        try {
            val opened = URL(StravoConfig.ACCOUNT_API_BASE + "/claim").openConnection() as HttpURLConnection
            connection = opened
            opened.connectTimeout = 15_000
            opened.readTimeout = 20_000
            opened.instanceFollowRedirects = false
            opened.requestMethod = "POST"
            opened.doOutput = true
            opened.setRequestProperty("Content-Type", "application/json")
            opened.setRequestProperty("Idempotency-Key", UUID.randomUUID().toString())
            opened.outputStream.bufferedWriter().use {
                it.write(JSONObject().put("code", code).put("device", deviceId).toString())
            }
            when (opened.responseCode) {
                403 -> return@withContext ImportOutcome.Failure(ImportError.DEVICE_LIMIT)
                400, 401, 404 -> return@withContext ImportOutcome.Failure(ImportError.LOGIN_REJECTED)
            }
            if (opened.responseCode !in 200..299) return@withContext ImportOutcome.Failure(ImportError.NETWORK)
            val customer = JSONObject(opened.inputStream.bufferedReader().use { it.readText() })
            if (!customer.optBoolean("active", false) || customer.optBoolean("disabled", false)) {
                return@withContext ImportOutcome.Failure(ImportError.SUBSCRIPTION_EXPIRED)
            }
            val source = customer.optString("sub_url")
            if (Uri.parse(source).scheme != "https") return@withContext ImportOutcome.Failure(ImportError.NETWORK)
            importer.import(source)
        } catch (_: Exception) {
            ImportOutcome.Failure(ImportError.NETWORK)
        } finally {
            connection?.disconnect()
        }
    }

    /** Override the sharer's device marker only on this service's public subscription routes. */
    fun subscriptionUrl(source: String): String {
        val uri = Uri.parse(source)
        val api = Uri.parse(StravoConfig.ACCOUNT_API_BASE)
        if (uri.host != api.host || uri.port != api.port || uri.scheme != "https" ||
            !(uri.path.orEmpty().startsWith("/sub/") || uri.path.orEmpty().startsWith("/cdn-sub/"))) return source
        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames.filter { it !in setOf("device", "platform") }.forEach { name ->
            uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
        }
        if (uri.getQueryParameter("format").isNullOrBlank()) builder.appendQueryParameter("format", "links")
        return builder.appendQueryParameter("device", deviceId)
            .appendQueryParameter("platform", if (DeviceType.formFactorOf(appContext).isTv) "tv" else "mobile")
            .build().toString()
    }
}
