# Контракты и что осталось подключить

Документ фиксирует границу между готовым клиентом и серверной частью.
Клиент **не имитирует** ни подключение, ни успешный перенос подписки.

---

## 1. Ядро туннеля

Интерфейс (реализация пока `UnavailableVpnEngine`):

```kotlin
interface VpnEngine {
    fun observeState(): StateFlow<VpnConnectionSnapshot>
    suspend fun connect(profile: VpnProfile?, location: VpnLocation)
    suspend fun disconnect()
}
```

Что должен предоставить сервис, чтобы подключить настоящее ядро:

1. **Контракт подписки** — обезличенный способ получить конфигурацию узлов: список локаций,
   протокол (VLESS / Hysteria2 / AnyTLS / WebRTC), параметры транспорта.
   В репозиторий попадает только код; URL подписки, UUID и ключи — никогда.
2. **Реализация `VpnService`** — поднятие туннеля, `Builder.establish()`, маршрутизация,
   обработка переподключений, отдача состояния в `VpnEngine`.
3. **Хранение секретов** — Android Keystore (`SecretStore`), без логирования и без бэкапа.

После появления ядра:
- `AppContainer.vpnEngine` заменяется на рабочую реализацию;
- UI и ViewModel менять не нужно — они уже работают через интерфейс;
- режим «Свободный интернет» остаётся **только на телефоне** (`CapabilityPolicy` fail-closed).

---

## 1a. Импорт подписки на клиенте (сделано)

Клиент умеет добавлять подписку сам: Профиль → Добавить подписку. Принимаются ссылка
подписки (https), одиночный ключ (vless://, anytls://, hysteria2://, trojan://, ss://) и QR.

Что уже разбирается: протокол, транспорт (TCP, WebSocket, HTTP Upgrade, **XHTTP**, gRPC, QUIC),
защита (TLS / Reality), порт, IPv6-хост; тело подписки — строки или base64; из заголовков
читаются subscription-userinfo (дата окончания) и profile-title (название плана).

Что остаётся серверной стороне:

1. Отдавать подписку по стабильной ссылке (формат — обычный base64-список ссылок Xray).
2. Поставлять заголовок subscription-userinfo, чтобы дата окончания была настоящей.
3. Вход по логину: клиент показывает поле, но подтверждение логина требует API сервиса.

Ядро забирает конфиг узла точечно: SubscriptionImporter.configFor(nodeId) → расшифрованный
конфиг из Android Keystore. В UI и в логи конфиг не попадает.

---

## 1b. Ядро туннеля: что ставим и как (в работе)

Ядро — **sing-box (libbox)**: только он закрывает весь заявленный набор — VLESS с транспортом
**XHTTP**, **AnyTLS**, Hysteria2, Trojan, Shadowsocks. У Xray нет AnyTLS, поэтому он не подходит.

Готового AAR нет ни в Maven Central, ни на JitPack, поэтому ядро собирается в CI:
workflow libbox.yml тянет закреплённую версию SagerNet/sing-box, собирает libbox.aar через
gomobile и кэширует результат по версии (иначе каждый прогон — 15–20 минут).

Дальше в приложении:

1. app/libs/libbox.aar + implementation(files("libs/libbox.aar")).
2. StravoVpnService : VpnService(), PlatformInterface — отдаёт ядру TUN через Builder.establish().
3. SingBoxVpnEngine : VpnEngine — старт/стоп сервиса и настоящее состояние в observeState().
4. SingBoxConfigBuilder — из ссылки узла (берётся из SecretStore) в JSON sing-box.
5. Манифест: foreground service, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS.

Правило остаётся: пока ядро не поднялось, приложение показывает честную ошибку и не рисует
«подключено».

---

## 1c. Покрытие протоколов ядрами (проверено по исходникам)

Ни одно ядро не закрывает весь заявленный список одним AAR:

| Ядро | vless | xhttp | anytls | hysteria2 | trojan | shadowsocks |
|---|---|---|---|---|---|---|
| sing-box v1.14.1 (libbox) | да | нет | да | да | да | да |
| Xray-core (libv2ray) | да | да | нет | нет | да | да |

Как проверялось:

- sing-box: constant/v2ray.go перечисляет транспорты http, ws, quic, grpc, httpupgrade;
  поиск xhttp по репозиторию даёт 0 совпадений (anytls — 20, hysteria2 — 25, поиск рабочий);
- Xray: xhttp есть (transport/internet/splithttp), а PR #5907 Add AnyTLS закрыт без мержа —
  мейнтейнер отказал; каталог proxy/ без anytls; hysteria в Xray — только v1;
- 2dust/AndroidLibXrayLite публикует готовый libv2ray.aar (около 59 МБ, внутрь зашиты
  geoip.dat и geosite.dat), сборка в CI тоже воспроизводима.

Решение: ставим **sing-box (libbox)** — он закрывает 5 пунктов из 6. Транспорт XHTTP
остаётся разобранным и сохранённым в подписке, но подключение к такому узлу обязано давать
честную ошибку «транспорт не поддерживается ядром этой сборки», а не тихий отказ.
Полный список = два ядра (+59 МБ и две среды исполнения) либо ожидание upstream.

---

## 1d. Ядро собрано: факты для интеграции

libbox.aar собран в CI из sing-box v1.14.1 (workflow libbox.yml, прогон зелёный,
две независимые сборки дали битово идентичный файл). ABI: arm64-v8a и armeabi-v7a.

Артефакты (вне репозитория, бинарники в git не кладём):

- libbox.aar — API 24, около 54 МиБ;
- libbox-legacy.aar — API 21, около 47 МиБ; classes.jar идентичен основному.

Ключ кэша, под которым AAR лежат в кэше Actions:
libbox-aar-v1.14.1-go1.26.8-gomobilev0.1.13-android-arm64-arm-v2
android.yml должен восстанавливать этот кэш в app/libs перед сборкой, а сам каталог
app/libs/*.aar — в .gitignore.

API (пакет io.nekohasekai.libbox, класс BoxService в этой версии отсутствует):

- Libbox.setup(SetupOptions), Libbox.newCommandServer(handler, platformInterface);
- CommandServer.start(), startOrReloadService(config, overrideOptions), closeService(), close();
- PlatformInterface (реализуется в Kotlin): openTun(TunOptions): Int, startDefaultInterfaceMonitor,
  autoDetectInterfaceControl, getInterfaces, findConnectionOwner, sendNotification и др.;
- TunOptions — только геттеры (getMTU, getInet4Address, getAutoRoute, getStrictRoute, ...);
- CommandServerHandler — интерфейс обратных вызовов (serviceReload, serviceStop, writeDebugMessage).

Решение по minSdk: остаёмся на 23 и берём libbox-legacy.aar (в основном AAR minSdkVersion 24,
manifest merger упал бы). Legacy отличается только отсутствием naive outbound — для наших
протоколов это не важно.

Размер: APK вырастет примерно на 70–80 МиБ, если класть оба ABI. Варианты — оставить
arm64 + arm (нужен v7a для ТВ-боксов) либо сделать splits по ABI.

---

## 2. Pairing-API (перенос подписки phone → TV)

Контракт, который нужен на стороне сервиса:

```
POST /pairing/session                    -> { "token": "...", "expires_at": <epoch_ms> }
GET  /pairing/session/{token}            -> { "status": "waiting|claimed|importing|success|expired" }
POST /pairing/session/{token}/claim      -> (вызывается ботом после подтверждения пользователем)
```

Правила, уже зашитые в клиент:

- QR содержит **только** токен: `https://t.me/<bot>?start=pair_<token>`;
- TTL — 5 минут, отсчёт виден на TV («Код обновится через mm:ss»);
- повторный claim отклоняется;
- секреты подписки в QR и в логах не появляются;
- состояния на TV: WAITING → CLAIMED → IMPORTING → SUCCESS, плюс EXPIRED и ERROR;
- после SUCCESS подписка обязана появиться в Профиле и Локациях (сейчас `SubscriptionRepository`
  наполняется только реальным путём импорта — `markSubscriptionImported` вызывается из кода импорта,
  а не из UI).

Пока API нет, `StubPairingBackend` возвращает `NotConfigured`, а экраны показывают честную подпись:
«Автоматический перенос включится, когда сервис опубликует pairing-API».

---

## 3. Telegram-бот

Конфигурация в одном месте — `core/StravoConfig.kt`:

```kotlin
const val BOT_USERNAME = "MaestroSecureVPN_bot"
const val START_PARAM_MOBILE = "stravo_quick_connect"
const val START_PARAM_TV = "stravo_tv_quick_connect"
const val START_PARAM_PAIR_PREFIX = "pair_"
```

Поведение телефона: `tg://resolve?domain=...&start=...` → `https://t.me/...?start=...` →
копирование ссылки в буфер с подсказкой. Лишние разрешения не запрашиваются, WebView не используется.

Поведение TV: Telegram обычно отсутствует, поэтому «Быстрое подключение» открывает экран с QR,
кнопкой «Открыть ссылку» и обратным отсчётом.

---

## 4. Что сознательно не сделано

- Нет фейкового «подключено» и фейковых цифр статистики: до измерений показывается длинное тире.
- Нет пользовательского пункта «Обход белых списков» — ни строк, ни состояния, ни навигации.
- Нет автотестов: по правилу владельца они не добавляются без отдельного разрешения.
