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
import com.stravo.vpn.engine.box.CoreControl
import com.stravo.vpn.engine.box.NetworkPolicy
import com.stravo.vpn.domain.model.SubscriptionSource
import com.stravo.vpn.data.subscription.SubscriptionRefreshJob
import com.stravo.vpn.data.subscription.ImportError
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StravoViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StravoApplication).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val subscriptionMutex = container.subscriptionMutex
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
    val appUpdates = container.appUpdates
    private val _vpnPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val vpnPermissionRequests = _vpnPermissionRequests.asSharedFlow()

    fun onVpnPermissionResult(granted: Boolean) {
        if (granted) toggleConnection(forceConnect = true)
        else _home.update { it.copy(connection = ConnectionState.Error(
            "Разрешение VPN не предоставлено. Нажмите подключить, чтобы повторить запрос.")) }
    }

    fun updateSettings(transform: (StravoSettings) -> StravoSettings) {
        val before = settings.value
        val proposed = transform(before)
        NetworkPolicy.validate(proposed)?.let { error ->
            _home.update { it.copy(diagnostics = error) }
            return
        }
        container.settings.update { proposed }
        val after = settings.value
        if (before.autoSelect != after.autoSelect) {
            val location = if (after.autoSelect) LocationsCatalog.AUTO else
                _home.value.tunnelLocation ?: _home.value.availableNodes.firstOrNull()?.let { _home.value.connectedLocation(it) }
                    ?: LocationsCatalog.AUTO
            _home.update { it.copy(location = location) }
            container.settings.update { it.copy(selectedNodeId = location.id) }
        }
        if (NetworkPolicy.requiresReconnect(before, after) || before.autoSelect != after.autoSelect ||
            before.autoReconnect != after.autoReconnect || before.probeUrl != after.probeUrl) {
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

    suspend fun exportBackup(password: CharArray): ByteArray = subscriptionMutex.withLock {
        try {
            com.stravo.vpn.data.backup.EncryptedBackup(getApplication(), container.secretStore).export(password)
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun restoreBackup(payload: ByteArray, password: CharArray): Boolean = subscriptionMutex.withLock {
        val backup = com.stravo.vpn.data.backup.EncryptedBackup(getApplication(), container.secretStore)
        val validated = try { backup.validate(payload, password) } finally { password.fill('\u0000') }
            ?: return@withLock false
        _home.update { it.copy(restoringBackup = true) }
        container.restoringBackup = true
        try {
            container.vpnEngine.disconnect()
            val stopped = withTimeoutOrNull(15_000) {
                container.vpnEngine.observeState().first {
                    !it.state.isActive && !it.state.isBusy
                }
            } != null
            if (!stopped) return@withLock false
            currentCoroutineContext().ensureActive()
            // Cancellation after durable writes must not leave repositories using old state.
            withContext(NonCancellable) {
                if (!backup.restore(validated)) return@withContext false
                container.settings.reload()
                if (!container.subscriptions.reload()) return@withContext false
                container.subscriptionImporter.migrateSource(container.subscriptions.nodes.value)
                _home.update { normalizedSources(it.copy(
                    subscriptionSources = container.subscriptions.sources.value,
                    subscriptionNodes = container.subscriptions.nodes.value,
                    selectedSourceId = "", location = LocationsCatalog.AUTO,
                    measuredNodePings = emptyMap(),
                )) }
                rememberSelection()
                SubscriptionRefreshJob.schedule(getApplication())
                true
            }
        } finally {
            validated.close()
            container.restoringBackup = false
            _home.update { it.copy(restoringBackup = false) }
        }
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
                    if (snapshot.state.isActive) {
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
            container.subscriptions.sources.collect { sources ->
                _home.update { normalizedSources(it.copy(subscriptionSources = sources)) }
                rememberSelection()
                SubscriptionRefreshJob.schedule(getApplication())
            }
        }
        scope.launch {
            container.subscriptions.nodes.collect { nodes ->
                _home.update { state ->
                    var next = normalizedSources(state.copy(
                        subscriptionNodes = nodes,
                        measuredNodePings = state.measuredNodePings.filterKeys { id -> nodes.any { it.id == id } },
                    ))
                    if (next.availableNodes.isNotEmpty() && state.notice == Notice.SUBSCRIPTION_REQUIRED) {
                        next = next.copy(connection = ConnectionState.Disconnected, notice = null)
                    }
                    // Узел мог исчезнуть или сменить идентификатор после обновления
                    // подписки: выбор, которого больше нет, честно сбрасываем на «Авто»,
                    // иначе «Подключить» отвечало бы «нет ключа узла».
                    if (next.location.id == LocationsCatalog.AUTO.id) {
                        next.locations.firstOrNull { it.id == settings.value.selectedNodeId }?.let {
                            next = next.copy(location = it)
                        }
                    }
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
        scope.launch {
            refreshSubscriptionIfStale()
            if (settings.value.autoConnect && !_home.value.connection.isActive && !_home.value.connection.isBusy &&
                _home.value.nodeForLocation() != null && android.net.VpnService.prepare(getApplication()) == null) {
                toggleConnection(forceConnect = true)
            }
        }
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
        val sources = if (force) container.subscriptions.sources.value.filter {
            it.enabled && importer.hasSourceUrl(it.id)
        } else container.subscriptions.dueSources()
        if (sources.isEmpty()) {
            if (force) _home.update { it.copy(subscriptionRefresh = SubscriptionRefreshState.NO_SOURCE) }
            return@withLock
        }
        var refreshPending = false
        for (source in sources) when (val outcome = importer.refreshSource(source.id)) {
            is ImportOutcome.Success -> {
                if (!container.subscriptions.applyImport(
                    subscription = outcome.subscription,
                    nodes = outcome.nodes,
                )) refreshPending = true
                container.coreTrace.record("подписка обновлена: узлов " + outcome.nodes.size)
                _home.update { it.copy(coreLog = coreLogLines()) }
            }

            is ImportOutcome.Failure -> {
                refreshPending = true
                container.subscriptions.markRefreshPending(source.id)
                container.coreTrace.record("подписка не обновилась: панель недоступна, узлы прежние")
            }
        }
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

    private fun normalizedSources(state: HomeUiState): HomeUiState {
        val sources = state.subscriptionSources.filter { it.enabled && CapabilityPolicy.permits(it.service, state.formFactor) }
        val wantedId = state.selectedSourceId.ifBlank { container.settings.settings.value.selectedSourceId }
        val source = sources.firstOrNull { it.id == wantedId }
            ?: sources.firstOrNull { it.service == if (state.mode == NetworkMode.FREE_INTERNET)
                SubscriptionService.CDN else SubscriptionService.ORDINARY }
        if (source == null) return state.copy(selectedSourceId = "", location = LocationsCatalog.AUTO)
        val mode = if (source.service == SubscriptionService.CDN) NetworkMode.FREE_INTERNET else NetworkMode.NORMAL_VPN
        return state.copy(selectedSourceId = source.id, mode = mode,
            location = if (state.selectedSourceId == source.id) state.location else LocationsCatalog.AUTO)
    }

    private fun rememberSelection() {
        val state = _home.value
        container.settings.update { it.copy(selectedSourceId = state.selectedSourceId, selectedMode = state.mode) }
    }

    fun selectSource(id: String) {
        val source = container.subscriptions.sources.value.firstOrNull {
            it.id == id && it.enabled && CapabilityPolicy.permits(it.service, deviceFormFactor)
        } ?: return
        val state = _home.value
        val running = container.vpnEngine.observeState().value.state.let { it.isActive || it.isBusy }
        if (state.selectedSourceId == id) {
            val actualId = container.vpnEngine.observeState().value.locationId
            val actualSource = container.subscriptions.nodes.value.firstOrNull { it.id == actualId }?.sourceId
            if (running && actualSource != id) toggleConnection(forceConnect = true)
            return
        }
        _home.update { it.copy(selectedSourceId = id, subscriptionSources = container.subscriptions.sources.value,
            mode = if (source.service == SubscriptionService.CDN) NetworkMode.FREE_INTERNET else NetworkMode.NORMAL_VPN,
            location = LocationsCatalog.AUTO, measuredNodePings = emptyMap()) }
        container.settings.update { it.copy(selectedSourceId = id, selectedMode = _home.value.mode,
            selectedNodeId = LocationsCatalog.AUTO.id) }
        if (running) toggleConnection(forceConnect = true)
    }

    fun refreshSource(id: String) {
        if (_home.value.subscriptionRefresh == SubscriptionRefreshState.LOADING) return
        _home.update { it.copy(subscriptionRefresh = SubscriptionRefreshState.LOADING) }
        scope.launch {
            subscriptionMutex.withLock {
                val importer = container.subscriptionImporter
                if (!importer.hasSourceUrl(id)) {
                    _home.update { it.copy(subscriptionRefresh = SubscriptionRefreshState.NO_SOURCE) }
                    return@withLock
                }
                val result = importer.refreshSource(id)
                val saved = result is ImportOutcome.Success &&
                    container.subscriptions.applyImport(result.subscription, result.nodes)
                _home.update { it.copy(subscriptionRefresh =
                    if (saved) SubscriptionRefreshState.UPDATED else SubscriptionRefreshState.FAILED) }
                if (saved && _home.value.selectedSourceId == id && _home.value.connection.isActive)
                    toggleConnection(forceConnect = true)
            }
        }
    }

    fun editSource(id: String, name: String, hours: Int) {
        scope.launch {
            subscriptionMutex.withLock {
                val saved = container.subscriptions.renameSource(id, name) &&
                    container.subscriptions.setSourceUpdateIntervalHours(id, hours)
                if (!saved) _home.update { it.copy(diagnostics = "Не удалось сохранить настройки подписки") }
                SubscriptionRefreshJob.schedule(getApplication())
            }
        }
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        scope.launch {
            subscriptionMutex.withLock {
                if (!enabled && _home.value.selectedSourceId == id) container.vpnEngine.disconnect()
                if (!container.subscriptions.setSourceEnabled(id, enabled))
                    _home.update { it.copy(diagnostics = "Не удалось изменить подписку") }
            }
        }
    }

    fun removeSource(id: String) {
        scope.launch {
            subscriptionMutex.withLock {
                if (_home.value.selectedSourceId == id) container.vpnEngine.disconnect()
                if (!container.subscriptions.removeSource(id))
                    _home.update { it.copy(diagnostics = "Не удалось удалить подписку") }
            }
        }
    }

    fun replaceSource(id: String, raw: String) {
        if (_home.value.subscriptionRefresh == SubscriptionRefreshState.LOADING) return
        _home.update { it.copy(subscriptionRefresh = SubscriptionRefreshState.LOADING) }
        scope.launch {
            subscriptionMutex.withLock {
                val old = container.subscriptions.sources.value.firstOrNull { it.id == id }
                val result = container.subscriptionImporter.import(raw)
                if (old == null || result !is ImportOutcome.Success ||
                    result.nodes.any { it.service != old.service } ||
                    !container.subscriptions.applyImport(result.subscription, result.nodes)) {
                    _home.update { it.copy(subscriptionRefresh = SubscriptionRefreshState.FAILED) }
                    return@withLock
                }
                val newId = result.sourceIds.firstOrNull() ?: id
                container.subscriptions.renameSource(newId, old.name)
                container.subscriptions.setSourceUpdateIntervalHours(newId, old.updateIntervalHours)
                container.subscriptions.setSourceEnabled(newId, old.enabled)
                val removed = id in result.sourceIds || container.subscriptions.removeSource(id)
                _home.update { it.copy(subscriptionRefresh =
                    if (removed) SubscriptionRefreshState.UPDATED else SubscriptionRefreshState.FAILED) }
                if (old.enabled) selectSource(newId)
            }
        }
    }

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
            HomeEvent.MeasureLocations -> measureLocations()
            HomeEvent.QuickConnectClick -> Unit
            HomeEvent.NoticeConsumed -> _home.update { it.copy(notice = null) }
            is HomeEvent.NoticeShown -> _home.update { it.copy(notice = event.notice) }

            is HomeEvent.LocationSelected -> {
                val location = _home.value.locations.firstOrNull { it.id == event.locationId } ?: return
                if (location.id == _home.value.location.id) return
                val connection = container.vpnEngine.observeState().value.state
                _home.update { it.copy(location = location) }
                container.settings.update { it.copy(selectedNodeId = location.id,
                    autoSelect = location.id == LocationsCatalog.AUTO.id) }
                if (connection.isActive || connection.isBusy) toggleConnection(forceConnect = true)
            }

            is HomeEvent.ModeSelected -> {
                val mode = CapabilityPolicy.normalizes(event.mode, _home.value.formFactor)
                val kind = if (mode == NetworkMode.FREE_INTERNET) SubscriptionService.CDN else SubscriptionService.ORDINARY
                _home.value.subscriptionSources.firstOrNull { it.enabled && it.service == kind }?.let {
                    selectSource(it.id)
                    return
                }
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
        if (_home.value.restoringBackup) return
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
        if (shouldConnect && android.net.VpnService.prepare(getApplication()) != null) {
            _vpnPermissionRequests.tryEmit(Unit)
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
                container.vpnEngine.connect(profile, current.connectedLocation(node),
                    automatic = current.location.id == LocationsCatalog.AUTO.id)
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

    /** Explicit user action; only nodes loaded into the current core/source are probed. */
    private fun measureLocations() {
        val state = _home.value
        if (state.measuringLocations || !state.connection.isActive) return
        _home.update { it.copy(measuringLocations = true, measuredNodePings = emptyMap()) }
        scope.launch {
            try {
                val gate = Semaphore(3)
                val control = CoreControl(container.coreApiToken.value)
                val target = settings.value.probeUrl
                val values = state.availableNodes.map { node ->
                    async(Dispatchers.IO) {
                        gate.withPermit { node.id to control.delay(CoreControl.tag(node.id), target, 4000) }
                    }
                }.awaitAll().mapNotNull { (id, delay) -> delay?.let { id to it } }.toMap()
                _home.update { it.copy(measuredNodePings = values) }
            } finally {
                _home.update { it.copy(measuringLocations = false) }
            }
        }
    }

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
                        val saved = container.subscriptions.applyImport(
                            subscription = outcome.subscription,
                            nodes = outcome.nodes,
                        )
                        if (!saved) {
                            _home.update { it.copy(importState = SubscriptionImportState.Failed(ImportError.SECRET_STORE)) }
                            return@withLock
                        }
                        if (!_home.value.connection.isActive && !_home.value.connection.isBusy) {
                            outcome.sourceIds.firstOrNull()?.let(::selectSource)
                        }
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
                if (!container.subscriptions.clear()) return@withLock
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

