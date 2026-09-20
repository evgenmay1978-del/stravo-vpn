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
import com.stravo.vpn.domain.subscription.SubscriptionService
import com.stravo.vpn.domain.model.VpnProfile
import com.stravo.vpn.domain.model.VpnStats
import com.stravo.vpn.domain.policy.CapabilityPolicy
import com.stravo.vpn.platform.DeviceType
import com.stravo.vpn.engine.box.CoreVariant
import com.stravo.vpn.pairing.PairingState
import com.stravo.vpn.pairing.PairingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StravoViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StravoApplication).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val subscriptionMutex = Mutex()
    private var routingUpdate: Job? = null

    private val deviceFormFactor = DeviceType.formFactorOf(application)
    private val _home = MutableStateFlow(HomeUiState(
        formFactor = deviceFormFactor,
        mode = CapabilityPolicy.normalizes(container.settings.settings.value.selectedMode, deviceFormFactor),
    ))
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _pairing = MutableStateFlow(PairingState())
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()

    val settings: StateFlow<StravoSettings> = container.settings.settings

    fun updateSettings(transform: (StravoSettings) -> StravoSettings) {
        val before = settings.value
        container.settings.update(transform)
        val after = settings.value
        if (before.appMode != after.appMode || before.apps != after.apps) {
            routingUpdate?.cancel()
            routingUpdate = scope.launch {
                delay(400)
                val connection = container.vpnEngine.observeState().value.state
                if (connection.isActive || connection.isBusy) toggleConnection(forceConnect = true)
            }
        }
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
                _home.update { state ->
                    val id = state.connectedLocationId
                    val ping = stats.pingMs
                    state.copy(
                        stats = stats,
                        measuredNodePings = if (state.connection.isActive && id != null && ping != null) {
                            state.measuredNodePings + (id to ping)
                        } else state.measuredNodePings,
                    )
                }
            }
        }
        scope.launch {
            container.subscriptions.subscription.collect { sub ->
                _home.update { it.copy(subscription = sub) }
            }
        }
        scope.launch {
            container.subscriptions.subscriptions.collect { plans ->
                _home.update { it.copy(subscriptionPlans = plans) }
            }
        }
        scope.launch {
            container.subscriptions.nodes.collect { nodes ->
                _home.update { state ->
                    var next = state.copy(
                        subscriptionNodes = nodes,
                        measuredNodePings = state.measuredNodePings.filterKeys { id -> nodes.any { it.id == id } },
                    )
                    // A raw CDN key has no ordinary companion URL. Select its real
                    // service instead of reporting that an imported profile is missing.
                    if (nodes.isNotEmpty() && next.availableNodes.isEmpty() &&
                        !state.connection.isActive && !state.connection.isBusy) {
                        val mode = if (nodes.any { it.service == SubscriptionService.ORDINARY }) {
                            NetworkMode.NORMAL_VPN
                        } else if (state.formFactor.isPhone && nodes.any { it.service == SubscriptionService.CDN }) {
                            NetworkMode.FREE_INTERNET
                        } else state.mode
                        next = next.copy(mode = mode, location = LocationsCatalog.AUTO)
                    }
                    if (next.availableNodes.isNotEmpty() && state.notice == Notice.SUBSCRIPTION_REQUIRED) {
                        next = next.copy(connection = ConnectionState.Disconnected, notice = null)
                    }
                    // Узел мог исчезнуть или сменить идентификатор после обновления
                    // подписки: выбор, которого больше нет, честно сбрасываем на «Авто»,
                    // иначе «Подключить» отвечало бы «нет ключа узла».
                    val selection = next.location
                    val stillThere = selection.id == LocationsCatalog.AUTO.id ||
                        next.availableNodes.any { it.id == selection.id }
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
    private suspend fun refreshSubscriptionIfStale(force: Boolean = false) = subscriptionMutex.withLock {
        val importer = container.subscriptionImporter
        if (!force && !container.subscriptions.needsRefresh()) return@withLock
        val sources = importer.sourceUrls
        if (sources.isEmpty()) {
            if (force) _home.update { it.copy(subscriptionRefresh = SubscriptionRefreshState.NO_SOURCE) }
            return@withLock
        }
        var refreshPending = false
        for (source in sources) when (val outcome = importer.refresh(source)) {
            is ImportOutcome.Success -> {
                container.subscriptions.applyImport(
                    subscription = outcome.subscription,
                    nodes = outcome.nodes,
                )
                container.coreTrace.record("подписка обновлена: узлов " + outcome.nodes.size)
                _home.update { it.copy(coreLog = coreLogLines()) }
            }

            is ImportOutcome.Failure -> {
                refreshPending = true
                container.coreTrace.record("подписка не обновилась: панель недоступна, узлы прежние")
            }
        }
        if (refreshPending) container.subscriptions.markRefreshPending()
        if (force) _home.update {
            it.copy(subscriptionRefresh = if (refreshPending) SubscriptionRefreshState.FAILED
                else SubscriptionRefreshState.UPDATED)
        }
    }

    fun refreshSubscriptions() {
        if (_home.value.subscriptionRefresh == SubscriptionRefreshState.LOADING ||
            _home.value.importState is SubscriptionImportState.Loading) return
        _home.update { it.copy(subscriptionRefresh = SubscriptionRefreshState.LOADING) }
        scope.launch {
            try {
                refreshSubscriptionIfStale(force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _home.update { it.copy(subscriptionRefresh = SubscriptionRefreshState.FAILED) }
            } finally {
                _home.update {
                    if (it.subscriptionRefresh == SubscriptionRefreshState.LOADING)
                        it.copy(subscriptionRefresh = SubscriptionRefreshState.IDLE) else it
                }
            }
        }
    }

    private var _profilesProtocolHint: String = "Авто (рекомендуется)"

    fun setFormFactor(formFactor: FormFactor) {
        _home.update {
            val next = it.copy(
                formFactor = formFactor,
                mode = CapabilityPolicy.normalizes(it.mode, formFactor),
            )
            if (next.locations.any { location -> location.id == next.location.id }) next
                else next.copy(location = LocationsCatalog.AUTO)
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
                if (location.id == _home.value.location.id) return
                val connection = container.vpnEngine.observeState().value.state
                _home.update { it.copy(location = location) }
                if (connection.isActive || connection.isBusy) toggleConnection(forceConnect = true)
            }

            is HomeEvent.ModeSelected -> {
                val mode = CapabilityPolicy.normalizes(event.mode, _home.value.formFactor)
                container.settings.update { it.copy(selectedMode = mode) }
                if (mode != _home.value.mode) {
                    scope.launch {
                        val connection = _home.value.connection
                        if (connection.isActive || connection.isBusy) container.vpnEngine.disconnect()
                        _home.update { it.copy(mode = mode, location = LocationsCatalog.AUTO) }
                    }
                }
            }

            is HomeEvent.SubscriptionSubmitted -> importSubscription(event.raw)
            is HomeEvent.LoginSubmitted -> importSubscription(event.login, isLogin = true)
            HomeEvent.SubscriptionImportCleared -> clearImportState()
            HomeEvent.SubscriptionRemoved -> removeSubscription()
        }
    }

    private fun toggleConnection(forceConnect: Boolean = false) {
        val current = _home.value
        val shouldConnect = forceConnect || !current.connection.isActive
        if (shouldConnect && current.nodeForLocation() == null) {
            // Подключать нечего: сначала подписка, потом ядро. Без выдуманного успеха.
            val reason = if (current.mode == NetworkMode.FREE_INTERNET) {
                "Добавьте CDN-подписку для режима «Свободный интернет»"
            } else {
                "Добавьте обычную VPN-подписку или войдите по Maestro login"
            }
            _home.update { it.copy(notice = Notice.SUBSCRIPTION_REQUIRED, connection = ConnectionState.Error(reason)) }
            return
        }
        scope.launch {
            if (shouldConnect) {
                val node = current.nodeForLocation() ?: return@launch
                if (!CapabilityPolicy.permits(node.service, current.formFactor)) return@launch
                val profile = VpnProfile(
                    id = node.id,
                    title = node.name,
                    protocolHint = node.protocolLabel + " · " + node.transportLabel,
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
    fun importSubscription(raw: String, isLogin: Boolean = false) {
        if (_home.value.importState is SubscriptionImportState.Loading) return
        _home.update { it.copy(importState = SubscriptionImportState.Loading) }
        scope.launch {
            subscriptionMutex.withLock {
                val outcome = if (isLogin) container.accountAccess.claim(raw, container.subscriptionImporter)
                    else container.subscriptionImporter.import(raw)
                when (outcome) {
                    is ImportOutcome.Success -> {
                        container.subscriptions.applyImport(
                            subscription = outcome.subscription,
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
    }

    fun clearImportState() {
        _home.update { it.copy(importState = SubscriptionImportState.Idle) }
    }

    fun removeSubscription() {
        // Ядро держит конфиг удаляемого узла в памяти: честнее выключить туннель,
        // чем оставлять его работающим без ключа в хранилище.
        scope.launch {
            subscriptionMutex.withLock {
                container.vpnEngine.disconnect()
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

