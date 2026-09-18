package com.stravo.vpn.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.stravo.vpn.StravoApplication
import com.stravo.vpn.core.StravoConfig
import com.stravo.vpn.data.subscription.ImportOutcome
import com.stravo.vpn.data.settings.StravoSettings
import com.stravo.vpn.domain.model.ConnectionState
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.domain.model.LocationsCatalog
import com.stravo.vpn.domain.model.NetworkMode
import com.stravo.vpn.domain.model.VpnProfile
import com.stravo.vpn.domain.model.VpnStats
import com.stravo.vpn.domain.policy.CapabilityPolicy
import com.stravo.vpn.engine.box.CoreVariant
import com.stravo.vpn.pairing.PairingState
import com.stravo.vpn.pairing.PairingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StravoViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StravoApplication).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _pairing = MutableStateFlow(PairingState())
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()

    val settings: StateFlow<StravoSettings> = container.settings.settings

    fun updateSettings(transform: (StravoSettings) -> StravoSettings) {
        container.settings.update(transform)
    }

    /** Сохраняет журнал ядра файлом в «Загрузки». Возвращает имя файла или null. */
    fun saveCoreLog(): String? =
        container.coreLogExporter.export(logToExport())

    /** Копирует журнал ядра в буфер обмена: так его видно даже без файла. */
    fun copyCoreLog(): Int {
        val lines = logToExport()
        container.clipboard.copy(lines.joinToString("\n"))
        return lines.size
    }

    /**
     * Журнал с шапкой: время, узел, вариант ядра, транспорт и последний шаг.
     *
     * Сам журнал в памяти вытесняется по 400 строк, поэтому хвост ядра без шапки не
     * говорит, на каком узле он снят; шапка берётся из [CoreTrace.lastContext] и
     * секретов не содержит.
     */
    private fun logToExport(): List<String> {
        val trace = container.coreTrace
        val header = ArrayList<String>()
        header.add("STRAVO VPN · журнал ядра · " + LOG_TIME_FORMAT.format(java.util.Date()))
        trace.lastContext()?.let { header.add(it) }
        trace.interruptedStep()?.let { header.add("последний шаг: " + it) }
        trace.lastCoreMessage()?.let { header.add("последнее сообщение ядра: " + it) }
        header.add("")
        return header + trace.logLines()
    }

    /** Текущий диагностический вариант ядра и его подпись для настроек. */
    fun coreVariant(): CoreVariant = container.coreTuning.variant()

    /** Следующий вариант по кругу: применится при следующем подключении. */
    fun nextCoreVariant(): CoreVariant = container.coreTuning.next()

    init {
        container.startupDiagnostics?.let { message ->
            _home.update { it.copy(diagnostics = message) }
        }
        container.vpnEngine.observeState().let { engineState ->
            scope.launch {
                engineState.collect { snapshot ->
                    _home.update {
                        it.copy(
                            connection = snapshot.state,
                            connectedLocationId = snapshot.locationId,
                            coreLog = coreLogLines(),
                        )
                    }
                    // Метрики собираем только у поднятого туннеля: у выключенного API
                    // ядра не отвечает, и экран честно показывает длинное тире.
                    if (snapshot.state is ConnectionState.Connected) {
                        container.tunnelStats.start(scope)
                    } else {
                        container.tunnelStats.stop()
                    }
                }
            }
        }
        scope.launch {
            container.tunnelStats.stats.collect { stats ->
                _home.update { it.copy(stats = stats) }
            }
        }
        scope.launch {
            container.subscriptions.subscription.collect { sub ->
                _home.update { it.copy(subscription = sub) }
            }
        }
        scope.launch {
            container.subscriptions.nodes.collect { nodes ->
                _home.update { state ->
                    val next = state.copy(subscriptionNodes = nodes)
                    // Узел мог исчезнуть или сменить идентификатор после обновления
                    // подписки: выбор, которого больше нет, честно сбрасываем на «Авто»,
                    // иначе «Подключить» отвечало бы «нет ключа узла».
                    val selection = next.location
                    val stillThere = selection.id == LocationsCatalog.AUTO.id ||
                        nodes.any { it.id == selection.id }
                    if (stillThere) next else next.copy(location = LocationsCatalog.AUTO)
                }
            }
        }
        scope.launch {
            container.profiles.profiles.collect { profiles ->
                _home.update { it.copy(profile = profiles.firstOrNull()) }
            }
        }
        scope.launch {
            container.settings.settings.collect { settings ->
                _profilesProtocolHint = settings.protocol
            }
        }
        scope.launch {
            container.pairing.state.collect { state -> _pairing.value = state }
        }
        // Подписка могла устареть: панель меняет параметры узлов (например, метод
        // отправки XHTTP), а сохранённые ссылки остаются прежними. Обновляем тихо,
        // из сохранённого источника; неудача не трогает уже добавленную подписку.
        scope.launch { refreshSubscriptionIfStale() }
    }

    /**
     * Тихое обновление подписки при старте, если её пора пересобрать (см.
     * [com.stravo.vpn.data.subscription.SubscriptionRepository.needsRefresh]).
     *
     * Ничего не показывает поверх интерфейса: успех и неудача попадают в журнал
     * ядра одной честной строкой, а узлы при неудаче остаются прежними.
     */
    private suspend fun refreshSubscriptionIfStale() {
        val importer = container.subscriptionImporter
        if (importer.sourceUrl == null) return
        if (!container.subscriptions.needsRefresh()) return
        when (val outcome = importer.refresh()) {
            is ImportOutcome.Success -> {
                container.subscriptions.applyImport(
                    planName = outcome.planName,
                    activeUntil = outcome.activeUntil,
                    nodes = outcome.nodes,
                )
                container.coreTrace.record("подписка обновлена: узлов " + outcome.nodes.size)
                _home.update { it.copy(coreLog = coreLogLines()) }
            }

            is ImportOutcome.Failure ->
                container.coreTrace.record("подписка не обновилась: панель недоступна, узлы прежние")

            null -> Unit
        }
    }

    private var _profilesProtocolHint: String = "Авто (рекомендуется)"

    fun setFormFactor(formFactor: FormFactor) {
        _home.update {
            it.copy(
                formFactor = formFactor,
                mode = CapabilityPolicy.normalizes(it.mode, formFactor),
            )
        }
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            HomeEvent.PowerClick -> toggleConnection()
            HomeEvent.Retry -> toggleConnection(forceConnect = true)
            HomeEvent.ProbeClick -> runProbe()
            HomeEvent.QuickConnectClick -> Unit
            HomeEvent.NoticeConsumed -> _home.update { it.copy(notice = null) }
            is HomeEvent.NoticeShown -> _home.update { it.copy(notice = event.notice) }

            is HomeEvent.LocationSelected -> {
                val location = _home.value.locations.firstOrNull { it.id == event.locationId } ?: return
                _home.update { it.copy(location = location) }
            }

            is HomeEvent.ModeSelected -> _home.update {
                it.copy(mode = CapabilityPolicy.normalizes(event.mode, it.formFactor))
            }

            is HomeEvent.SubscriptionSubmitted -> importSubscription(event.raw)
            HomeEvent.SubscriptionImportCleared -> clearImportState()
            HomeEvent.SubscriptionRemoved -> removeSubscription()
        }
    }

    private fun toggleConnection(forceConnect: Boolean = false) {
        val current = _home.value
        val shouldConnect = forceConnect || !current.connection.isActive
        if (shouldConnect && current.formFactor.isPhone && !current.subscription.isActive && current.profile == null) {
            // Подключать нечего: сначала подписка, потом ядро. Без выдуманного успеха.
            _home.update { it.copy(notice = Notice.SUBSCRIPTION_REQUIRED) }
            return
        }
        scope.launch {
            if (shouldConnect) {
                val node = current.nodeForLocation()
                val profile = current.profile ?: VpnProfile(
                    id = node?.id ?: "default",
                    title = node?.name ?: current.subscription.planName,
                    protocolHint = node?.let { it.protocolLabel + " · " + it.transportLabel }
                        ?: _profilesProtocolHint,
                )
                container.vpnEngine.connect(profile, current.connectedLocation(node))
            } else {
                container.vpnEngine.disconnect()
            }
        }
    }

    /** Самопроверка туннеля: строки пробы попадают в журнал ядра на главном экране. */
    private fun runProbe() {
        if (_home.value.probing) return
        _home.update { it.copy(probing = true) }
        scope.launch {
            try {
                container.tunnelProbe.run()
            } finally {
                _home.update { it.copy(probing = false, coreLog = coreLogLines()) }
            }
        }
    }

    private fun coreLogLines(): List<String> =
        container.coreTrace.logLines().takeLast(CORE_LOG_LINES)

    // --- Подписка ---------------------------------------------------------

    /**
     * Добавление подписки. Сырая ссылка живёт только внутри импортёра и защищённого
     * хранилища: в состояние UI попадают лишь безопасные карточки узлов.
     */
    fun importSubscription(raw: String) {
        if (_home.value.importState is SubscriptionImportState.Loading) return
        _home.update { it.copy(importState = SubscriptionImportState.Loading) }
        scope.launch {
            when (val outcome = container.subscriptionImporter.import(raw)) {
                is ImportOutcome.Success -> {
                    container.subscriptions.applyImport(
                        planName = outcome.planName,
                        activeUntil = outcome.activeUntil,
                        nodes = outcome.nodes,
                    )
                    _home.update {
                        it.copy(
                            importState = SubscriptionImportState.Done(outcome.nodes.size),
                            notice = Notice.SUBSCRIPTION_ADDED,
                        )
                    }
                }

                is ImportOutcome.Failure -> _home.update {
                    it.copy(
                        importState = SubscriptionImportState.Failed(
                            error = outcome.error,
                            reason = outcome.reason,
                            token = outcome.token,
                        ),
                    )
                }
            }
        }
    }

    fun clearImportState() {
        _home.update { it.copy(importState = SubscriptionImportState.Idle) }
    }

    fun removeSubscription() {
        // Ядро держит конфиг удаляемого узла в памяти: честнее выключить туннель,
        // чем оставлять его работающим без ключа в хранилище.
        scope.launch { container.vpnEngine.disconnect() }
        container.subscriptions.nodes.value.forEach { node -> container.secretStore.remove(node.id) }
        container.subscriptionImporter.forgetSource()
        container.subscriptions.clear()
        _home.update {
            it.copy(
                importState = SubscriptionImportState.Idle,
                location = LocationsCatalog.AUTO,
                notice = Notice.SUBSCRIPTION_REMOVED,
            )
        }
    }

    /** Конфиг выбранного узла для ядра туннеля. В UI это значение не выводится. */
    fun secretConfigFor(locationId: String): String? =
        container.subscriptionImporter.configFor(locationId)

    // --- Перенос подписки phone → TV -------------------------------------

    private val _pairingToken = MutableStateFlow<String?>(null)
    val pairingToken: StateFlow<String?> = _pairingToken.asStateFlow()

    private val _pairingSecondsLeft = MutableStateFlow(0L)
    val pairingSecondsLeft: StateFlow<Long> = _pairingSecondsLeft.asStateFlow()

    private var tickerJob: Job? = null

    /** Создаёт одноразовую сессию и включает обратный отсчёт. Секретов в токене нет. */
    suspend fun beginPairing(): String? {
        val session = container.pairing.start()
        _pairingToken.value = session.token
        _pairingSecondsLeft.value = session.secondsLeftAt(System.currentTimeMillis())
        startPairingTicker()
        return session.token
    }

    /** Обновить QR: старая сессия закрывается, создаётся новый одноразовый токен. */
    suspend fun renewPairing() {
        container.pairing.reset()
        beginPairing()
    }

    fun refreshPairingExpiry() {
        container.pairing.refreshExpiry()
    }

    private fun startPairingTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                val session = container.pairing.currentSession()
                _pairingSecondsLeft.value = session?.secondsLeftAt(System.currentTimeMillis()) ?: 0L
                container.pairing.refreshExpiry()
                delay(1000L)
            }
        }
    }

    suspend fun pollPairing() {
        container.pairing.poll()
    }

    fun resetPairing() {
        container.pairing.reset()
    }

    fun markSubscriptionImported(planName: String, activeUntil: String?) {
        // Вызывается только реальным путём импорта, когда pairing-API подтвердит перенос.
        container.subscriptions.setActive(planName, activeUntil)
    }

    /** Подписка ещё не подключена — это честный признак, а не заглушка в UI. */
    val hasSubscription: Boolean get() = container.subscriptions.subscription.value.isActive

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    companion object {
        /** Сколько последних строк журнала ядра показываем на главном экране. */
        const val CORE_LOG_LINES = 40

        /** Время в шапке выгружаемого журнала. */
        private val LOG_TIME_FORMAT = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.US)

        const val STATS_PLACEHOLDER: String = VpnStats.PLACEHOLDER
        val CONNECTED_STATE: ConnectionState = ConnectionState.Connected
        val PAIRING_SUCCESS: PairingStatus = PairingStatus.SUCCESS
        val PROTOCOLS: List<String> = StravoConfig.PROTOCOLS
        val NETWORK_MODE_FREE: NetworkMode = NetworkMode.FREE_INTERNET
    }
}

