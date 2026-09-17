package com.stravo.vpn.data

import android.content.Context
import com.stravo.vpn.data.diagnostics.Clipboard
import com.stravo.vpn.data.diagnostics.CoreLogExporter
import com.stravo.vpn.data.diagnostics.CoreLogReader
import com.stravo.vpn.data.diagnostics.CoreTrace
import com.stravo.vpn.data.diagnostics.StartupDiagnostics
import com.stravo.vpn.data.diagnostics.TunnelProbe
import com.stravo.vpn.data.profile.ProfileRepository
import com.stravo.vpn.data.secret.SecretStore
import com.stravo.vpn.data.settings.SettingsRepository
import com.stravo.vpn.data.subscription.SubscriptionImporter
import com.stravo.vpn.data.subscription.SubscriptionRepository
import com.stravo.vpn.domain.engine.UnavailableVpnEngine
import com.stravo.vpn.domain.engine.VpnConfigProvider
import com.stravo.vpn.domain.engine.VpnEngine
import com.stravo.vpn.engine.box.CoreTuning
import com.stravo.vpn.engine.box.SingBoxVpnEngine
import com.stravo.vpn.engine.box.TunnelCore
import com.stravo.vpn.pairing.PairingRepository

/** Ручная DI-обвязка: один контейнер на приложение, без магии. */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val settings: SettingsRepository = SettingsRepository(appContext)

    val profiles: ProfileRepository = ProfileRepository()

    /** Подписка переживает перезапуск: план, дата и безопасные карточки узлов. */
    val subscriptions: SubscriptionRepository = SubscriptionRepository(appContext)

    /** Полные конфиги узлов подписки: только Keystore, только по запросу ядра. */
    val secretStore: SecretStore = SecretStore(appContext)

    val subscriptionImporter: SubscriptionImporter = SubscriptionImporter(secretStore)

    val pairing: PairingRepository = PairingRepository()

    /** Шаги запуска ядра: нужны, чтобы честно объяснить аварийное завершение. */
    val coreTrace: CoreTrace = CoreTrace(appContext)

    /** Самопроверка туннеля: внешний адрес со стороны узла и контрольный запрос. */
    val tunnelProbe: TunnelProbe = TunnelProbe(appContext, coreTrace)

    /** Журнал самого ядра (libbox пишет его в файл рядом с данными приложения). */
    val coreLogReader: CoreLogReader = CoreLogReader(appContext, coreTrace)

    /** Выгрузка журнала в «Загрузки»: так его видно без системного logcat. */
    val coreLogExporter: CoreLogExporter = CoreLogExporter(appContext)

    /** Копирование журнала в буфер обмена: быстрый путь передать его текстом. */
    val clipboard: Clipboard = Clipboard(appContext)

    /** Диагностический вариант сборки конфига ядра: переключается на живом устройстве. */
    val coreTuning: CoreTuning = CoreTuning(appContext)

    /** Одна строка о прошлом запуске, если он завершился нештатно. Показывается один раз. */
    val startupDiagnostics: String? = StartupDiagnostics(appContext).message

    private val configProvider: VpnConfigProvider =
        VpnConfigProvider { nodeId -> subscriptionImporter.configFor(nodeId) }

    /**
     * Ядро — sing-box (libbox), см. docs/IMPLEMENTATION.md, разделы 1b–1d.
     * Если нативная часть не загрузилась (чужой ABI, обрезанный APK), приложение
     * честно сообщает об этом и не рисует «подключено».
     */
    val vpnEngine: VpnEngine = if (TunnelCore.isAvailable) {
        SingBoxVpnEngine(context = appContext, configProvider = configProvider)
    } else {
        UnavailableVpnEngine(
            configProvider = configProvider,
            reason = UnavailableVpnEngine.NATIVE_FAILED_REASON,
        )
    }
}

