package com.stravo.vpn.data.diagnostics

import com.stravo.vpn.domain.model.VpnStats
import com.stravo.vpn.engine.box.SingBoxConfigBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Метрики соединения для главного экрана: пинг, загрузка и отдача.
 *
 * Источник — локальный API ядра (Clash API на петлевом адресе, см.
 * [SingBoxConfigBuilder.CLASH_API_PORT]). Ничего не выдумывается: пока данных нет,
 * поля остаются null и интерфейс показывает длинное тире. Наружу (в сеть, в журнал,
 * в UI-модели) уходят только числа — ни адресов узлов, ни ключей здесь нет.
 *
 * Что берём:
 * - `GET /traffic` — поток строк `{"up":N,"down":N}` (байт в секунду) → Мбит/с;
 * - `GET /proxies/proxy/delay` — задержка проверки узла в миллисекундах.
 */
class TunnelStats(
    private val secret: String,
    private val port: Int = SingBoxConfigBuilder.CLASH_API_PORT,
) {

    private val _stats = MutableStateFlow(VpnStats.Empty)
    val stats: StateFlow<VpnStats> = _stats.asStateFlow()

    private var trafficJob: Job? = null
    private var pingJob: Job? = null

    /** Запускает сбор метрик, пока туннель поднят. Повторный вызов ничего не ломает. */
    fun start(scope: CoroutineScope) {
        if (trafficJob?.isActive == true) return
        _stats.value = VpnStats.Empty
        trafficJob = scope.launch(Dispatchers.IO) { trafficLoop() }
        pingJob = scope.launch(Dispatchers.IO) { pingLoop() }
    }

    /** Останавливает сбор и возвращает прочерки: у выключенного туннеля метрик нет. */
    fun stop() {
        trafficJob?.cancel()
        pingJob?.cancel()
        trafficJob = null
        pingJob = null
        _stats.value = VpnStats.Empty
    }

    /**
     * Скорость: ядро отдаёт по строке в секунду, соединение живёт до разрыва.
     * Единицы — байты в секунду, на экран уходят мегабиты в секунду.
     */
    private suspend fun trafficLoop() {
        while (currentCoroutineContext().isActive) {
            var connection: HttpURLConnection? = null
            try {
                connection = open("/traffic", TRAFFIC_READ_TIMEOUT_MS)
                connection.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!line.startsWith("{")) continue
                        val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        val down = json.optLong("down", 0L)
                        val up = json.optLong("up", 0L)
                        _stats.update {
                            it.copy(downloadMbps = toMbps(down), uploadMbps = toMbps(up))
                        }
                    }
                }
            } catch (_: Throwable) {
                // Ядро ещё не подняло API или соединение закрылось: ждём и пробуем снова.
            } finally {
                runCatching { connection?.disconnect() }
            }
            delay(RETRY_DELAY_MS)
        }
    }

    /** Пинг: проверка узла через сам узел — тем же путём, что и остальной трафик. */
    private suspend fun pingLoop() {
        while (currentCoroutineContext().isActive) {
            val delayMs = runCatching { requestDelay() }.getOrNull()
            if (delayMs != null) {
                _stats.update { it.copy(pingMs = delayMs) }
            }
            delay(PING_INTERVAL_MS)
        }
    }

    private fun requestDelay(): Int? {
        val connection = open(
            "/proxies/" + PROXY_TAG + "/delay?timeout=" + PING_TIMEOUT_MS + "&url=" + PING_URL,
            PING_TIMEOUT_MS + TIMEOUT_SLACK_MS,
        )
        return try {
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optInt("delay", -1).takeIf { it > 0 }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun open(path: String, readTimeoutMs: Int): HttpURLConnection =
        (URL("http://" + LOOPBACK + ":" + port + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            this.readTimeout = readTimeoutMs
            requestMethod = "GET"
            useCaches = false
            if (secret.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer " + secret)
            }
        }

    private fun toMbps(bytesPerSecond: Long): Double = bytesPerSecond * 8.0 / 1_000_000.0

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PROXY_TAG = "proxy"

        /**
         * Проверка узла: тот же адрес, что ядро берёт по умолчанию, когда URL не задан
         * (`common/urltest` → https://www.gstatic.com/generate_204). Задаём явно, чтобы
         * поведение не менялось вместе с ядром. Открытый http:// ядро отклоняет.
         */
        const val PING_URL = "https%3A%2F%2Fwww.gstatic.com%2Fgenerate_204"
        const val PING_TIMEOUT_MS = 3000
        const val PING_INTERVAL_MS = 20_000L
        const val TRAFFIC_READ_TIMEOUT_MS = 5000
        const val CONNECT_TIMEOUT_MS = 1500
        const val TIMEOUT_SLACK_MS = 1000
        const val RETRY_DELAY_MS = 1000L
    }
}
