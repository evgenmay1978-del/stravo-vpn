package com.stravo.vpn.data.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Самопроверка туннеля: спрашивает у внешнего сервиса, каким адресом его видно.
 *
 * Нужна, чтобы отличить «трафик пошёл через узел» от «трафик ушёл мимо»: строки пробы
 * попадают в [CoreTrace] и видны на главном экране. Само приложение исключено из
 * туннеля (см. openTun), поэтому проба честно показывает внешний адрес устройства.
 */
class TunnelProbe(private val context: Context, private val trace: CoreTrace) {

    /** Две пробы: со стороны узла и напрямую по IP, без DNS. */
    suspend fun run() = withContext(Dispatchers.IO) {
        trace.record("проба: сеть по умолчанию — " + activeNetwork())
        trace.record("проба: адрес узла — " + fetch(TRACE_URL))
        trace.record("проба: контрольный — " + fetch(CONTROL_URL))
    }

    /** Как система видит текущую сеть: есть ли на ней VPN-транспорт. */
    private fun activeNetwork(): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "неизвестно"
        val network = manager.activeNetwork ?: return "нет сети"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "нет данных"
        val transports = ArrayList<String>()
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.add("vpn")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("wifi")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("cellular")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("ethernet")
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return (if (transports.isEmpty()) "other" else transports.joinToString("+")) +
            if (validated) ", validated" else ", не проверена"
    }

    private fun fetch(url: String): String {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code !in 200..299) return "HTTP " + code
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            summarise(body)
        } catch (error: Throwable) {
            (error.message ?: error.javaClass.simpleName).take(80)
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** Оставляем только то, что нужно для ответа: адрес, страну и колокацию. */
    private fun summarise(body: String): String {
        val text = body.trim()
        if (text.startsWith("{")) {
            return text.take(120)
        }
        val fields = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val index = line.indexOf('=')
            if (index > 0) fields[line.substring(0, index)] = line.substring(index + 1)
        }
        val parts = ArrayList<String>()
        fields["ip"]?.let { parts.add("ip " + it) }
        fields["loc"]?.let { parts.add("страна " + it) }
        fields["colo"]?.let { parts.add("узел " + it) }
        return if (parts.isEmpty()) text.take(120) else parts.joinToString(", ")
    }

    companion object {
        private const val TRACE_URL = "https://1.1.1.1/cdn-cgi/trace"
        private const val CONTROL_URL = "https://api.ipify.org?format=json"
        private const val TIMEOUT_MS = 8000
    }
}
