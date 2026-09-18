# Контракты и что осталось подключить

Документ фиксирует границу между готовым клиентом и серверной частью.
Клиент **не имитирует** ни подключение, ни успешный перенос подписки.

---

## 1. Ядро туннеля

Интерфейс (две реализации: `SingBoxVpnEngine` — рабочее ядро, `UnavailableVpnEngine` — честный
ответ, если нативная часть не загрузилась):

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

Форматы входа: список ссылок, base64 от него, JSON-массив конфигов Xray (?format=xray) и JSON-конфиг
Clash/mihomo (?format=mihomo) — последние приводятся к обычным share-ссылкам, дальше путь один.
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

## 1b. Ядро туннеля: что ставим и как (сделано)

Ядро — **sing-box (libbox)**: только он закрывает весь заявленный набор — VLESS с транспортом
**XHTTP**, **AnyTLS**, Hysteria2, Trojan, Shadowsocks. У Xray нет AnyTLS, поэтому он не подходит.

Готового AAR нет ни в Maven Central, ни на JitPack, поэтому ядро собирается в CI:
workflow libbox.yml тянет закреплённую версию SagerNet/sing-box, собирает libbox.aar через
gomobile и кэширует результат по версии (иначе каждый прогон — 15–20 минут).

Сделано в приложении:

1. `app/libs/libbox-legacy.aar` (minSdk 23) + `implementation(files(libs/libbox-legacy.aar))`;
   AAR в git не лежит, его кладёт CI: job `core` в android.yml → переиспользуемый libbox.yml.
2. `engine/box/StravoVpnService` : VpnService(), PlatformInterface — отдаёт ядру TUN через
   `Builder.establish()`, реализует `openTun`, `autoDetectInterfaceControl` (protect),
   `getInterfaces`, монитор сети по умолчанию; остальные методы честные no-op.
3. `engine/box/SingBoxVpnEngine` : VpnEngine — старт/стоп сервиса, состояние ядра в observeState().
4. `engine/box/SingBoxConfigBuilder` — из ссылки узла (берётся из SecretStore) в JSON sing-box.
5. `engine/box/TunnelCore` — проверка, что нативные .so загрузились; иначе `UnavailableVpnEngine`.
6. Манифест: foreground service типа dataSync, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC,
   POST_NOTIFICATIONS, BIND_VPN_SERVICE. MainActivity один раз спрашивает системное разрешение VPN.

Правило остаётся: пока ядро не поднялось, приложение показывает честную ошибку и не рисует
«подключено». Ошибки ядра отдаются текстом, конфиг и ключи в состояние и логи не попадают.

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

## 1d. Ядро собрано и подключено: факты интеграции

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

Что именно использует приложение из этого API:

- `Libbox.setup(SetupOptions)` — basePath/workingPath/tempPath, logMaxLines 200, appVersion;
- `Libbox.newCommandServer(handler, platform)` → `start()` → `startOrReloadService(json, null)`;
  остановка — `closeService()` + `close()`;
- `PlatformInterface.openTun(TunOptions)` — адреса, DNS, маршруты, MTU, exclude/includepackage,
  `establish()` и `detachFd()`;
- `PlatformInterface.startDefaultInterfaceMonitor` — минимальный монитор на
  `ConnectivityManager.registerDefaultNetworkCallback`;
- `CommandServerHandler` — `serviceStop` останавливает туннель, остальное no-op.

Ограничения, которые остаются честными:

- транспорт **XHTTP** поддержан ядром из форка (`with_xhttp`, раздел 1i); неподдержанным
  остаётся только то, чего в сборке нет, и тогда UI честно пишет «транспорт не поддерживается
  ядром этой сборки»;
- в AAR только arm64-v8a и armeabi-v7a: на x86 APK установится, но туннель не поднимется —
  `TunnelCore` вернёт false и приложение честно скажет об этом;
- ABI-splits не делаем: один APK для телефона и TV, поэтому размер растёт на ~47 МиБ.

Размер: APK вырастет примерно на 70–80 МиБ, если класть оба ABI. Варианты — оставить
arm64 + arm (нужен v7a для ТВ-боксов) либо сделать splits по ABI.

---

## 1e. Грабли интеграции (проверено на устройстве)

Обе находки стоили аварийного завершения процесса и теперь закрыты:

1. `CommandServer.startOrReloadService(config, options)` **не принимает null**:
   Go-код читает `options.AutoRedirect` без проверки, и пустая ссылка роняет
   процесс нативно (в логе — REASON_CRASH_NATIVE). Передаём `OverrideOptions()`.
2. `PlatformInterface.getInterfaces()` обязан отдавать адреса в формате CIDR:
   sing-box разбирает их через `netip.MustParsePrefix`, а паника в Go убивает
   процесс. Адрес берётся из `interfaceAddresses` (адрес + длина префикса),
   у IPv6 отрезается scope-суффикс.

3. **Трафик выпускает Java-слой, а не ядро.** libbox на Android всегда идёт в
   параллельный диалер по интерфейсам, которые ему отдал `getInterfaces()`, а сам
   список заполняется только из колбэка `startDefaultInterfaceMonitor`
   (`experimental/libbox/monitor.go` — единственный вызов `UpdateInterfaces()`).
   Диалеру нужен интерфейс с тем же индексом, что сообщил монитор; иначе —
   `no available network interface` и ни одного соединения, включая DNS, при
   внешне поднятом туннеле. Поэтому монитор обязан:
   - отдать текущую сеть **сразу** при старте (а не ждать смены сети),
   - повторять попытки, пока `LinkProperties` и интерфейс не станут доступны,
   - сообщать честное «сети нет» (`""`, индекс `-1`), а не выдуманный индекс 0:
     с индексом 0 ядро уходит в привязку к интерфейсу (`SO_BINDTODEVICE`),
     которую Android обычному приложению не разрешает.
4. **Флаги интерфейса — константы Linux `IFF_*`** (`IFF_UP=0x1`, `IFF_LOOPBACK=0x8`,
   `IFF_POINTOPOINT=0x10`, `IFF_RUNNING=0x40`, `IFF_MULTICAST=0x1000`): libbox
   переводит их в `net.Flags` своим `link_flags_unix.go`. Значения Go `net.Flags`
   (4/8/16) дают интерфейс без `IFF_RUNNING` — ядро его не видит.
5. **Список приложений нельзя смешивать**: `addAllowedApplication` и
   `addDisallowedApplication` в одном `Builder` запрещены, исключение молча
   глоталось и раздельный туннель мог не примениться. Своё приложение исключаем
   первым и только в режиме «все, кроме выбранных».
6. **Разбор протокола — только правилом, не полем inbound.** В sing-box 1.11+ поля
   `sniff`, `sniff_override_destination`, `domain_strategy` в inbound объявлены
   legacy, а с 1.13 проверка стала жёсткой: `initialize inbound[0]: legacy inbound
   fields are deprecated` — и ядро вообще не стартует. Разбор включается правилом
   `{"action":"sniff"}` в `route.rules`, за ним идёт `{"protocol":"dns","action":"hijack-dns"}`.
   Для tun это ещё и `address`/`route_address` вместо старых `inet4_address`/`inet4_route_address`.
7. **`SetupOptions.setDebug(true)`** — без него ядро не зовёт `writeDebugMessage`,
   и «последнее сообщение ядра» всегда пустое. Сам журнал ядра libbox пишет в
   `filesDir/CrashReport-*.log` (и в `crash_reports`), его читает `CoreLogReader`,
   а `CoreLogExporter` кладёт `stravo-core.log` в «Загрузки».

Плюс к этому: `app/libs/*.aar` нужен до конфигурации Gradle (иначе понятная
ошибка), а состояние ядра и подписки должно переживать падение процесса —
для этого в приложении есть `CoreTrace` и `StartupDiagnostics`.

---

## 1f. REALITY: отпечаток uTLS обязателен и только один (найдено 18.09.2026)

**Симптом.** Любая попытка выйти в узел заканчивалась
`dial UDP connection: reality verification failed` — ни DNS, ни TCP через узел не шли.
Те же узлы в INCY/Happ (ядро Xray) работали.

**Причина на стороне сервера.** 08.09.2026 в библиотеке REALITY (`xtls/reality`) появился
коммит `8cdf7bf9` — «Reality protocol: Reject outdated/strange Client Hello that doesn't
have X25519MLKEM768 before optional X25519»; он вошёл в Xray-core v26.9.8/v26.9.9 (08.09.2026).
Сервер, разбирая ClientHello, требует гибридную пост-квантовую долю ключа
**X25519MLKEM768 (0x11ec) перед** обычной X25519 (0x001d):

```go
if peerPub2 == nil {
    break // reject outdated/strange Client Hello that doesn't have X25519MLKEM768 …
}
```

Без неё сервер не считает клиента REALITY-клиентом и молча уводит соединение на настоящий
сайт-заглушку (`dest`), ретранслируя ответ. Клиент получает настоящий сертификат, не находит
в нём REALITY-подпись (HMAC по ed25519-ключу) и падает с `reality verification failed`.

**Причина на нашей стороне.** Ядро sing-box 1.14.1 собрано с `metacubex/utls v1.8.7`.
Гибридную долю несёт только `HelloChrome_133` (отпечаток `chrome`: GREASE, X25519MLKEM768,
X25519). Остальные отпечатки, которые понимает sing-box, отправляют только X25519:
`firefox` → X25519+P256, `safari` → GREASE+X25519, `ios` → GREASE+X25519, `edge` → X25519+P256,
`android`/`360`/`qq` — то же, `random` — один из пяти (шанс 1/5), `randomized` — свой набор.
Такой ClientHello новый сервер отбрасывает. До 08.09.2026 отпечаток значения не имел.

**Решение** (`SingBoxConfigBuilder.applyTls`): для REALITY отпечаток из ссылки не берётся,
всегда ставится `chrome` (константа `REALITY_FINGERPRINT`). Для не-REALITY TLS отпечаток
из ссылки по-прежнему уважается. Побочный эффект приятный: ссылка без `fp` больше не даёт
`uTLS is required by reality client` — раньше блок `utls` в конфиг не попадал вовсе.

**Как убедиться, что починилось.** В журнале ядра после подключения должны появиться
`outbound/vless[proxy]: outbound connection to <ip>:443` и `dns: exchanged … NOERROR`,
а страницы — открываться.

**Если узел снова отвалится с той же строкой** — проверять по порядку: версия Xray на узле
(≥ v26.9.8 требует PQ-долю), совпадение `pbk`/`sid`/`sni` с панелью, доступность узла.
Клиенты на Xray (INCY, Happ, v2rayNG) отправляют эту долю всегда — поэтому «у них работает,
а у нас нет» означает проблему в нашем ClientHello, а не в узле.

---

## 1g. Стек TUN: по умолчанию gVisor, а не mixed (найдено 18.09.2026)

**Симптом.** REALITY уже проходит, UDP (DNS, QUIC) ходит, но TCP-соединений нет ни одного:
в журнале за 25 секунд работы YouTube — 34 строки `router: pre-match[0] => sniff`,
5 пар `inbound packet connection from/to` (это UDP) и **ноль** `inbound connection from`.

**Разбор.** `pre-match[0] => sniff` для TCP печатает `Router.PreMatch` (`route/route.go`):
для не-UDP потока правило `sniff` сразу отдаёт `PreMatchContinue`. Дальше
`ForwardDispatcher.judgeAndInstall` (`sing-tun/flow_dispatch.go`) ставит вердикт
`ActionAccept` и пакет **не** забирает — он уходит в `Mixed.processIPv4` →
`System.processIPv4TCP`, то есть в системный стек: пакет переписывается
(src — второй адрес TUN, dst — адрес TUN + NAT-порт) и возвращается в TUN, чтобы ядро
отдало его слушающему сокету, из которого sing-box и создаёт `inbound connection`.
На этом устройстве последний шаг не срабатывает: SYN-ы судятся и исчезают.

Почему так — по исходникам: `docs/configuration/inbound/tun.md` («`mixed` = Mixed `system`
TCP stack and `gvisor` UDP stack»), `sing-tun/stack.go` (`NewStack("mixed") = NewMixed`),
`stack_mixed.go: processIPv4` (TCP → `System.processIPv4TCP`, UDP → gVisor). Значит в
`mixed` и `system` **весь TCP идёт через системный стек**, и gVisor для TCP не используется
вовсе — вопреки тому, что подсказывает название. Клиенты на Xray (INCY, Happ, v2rayNG)
работают через gVisor: в `assets/v2ray_config_with_tun.json` у v2rayNG поля `stack` нет,
то есть берётся значение Xray по умолчанию — gVisor.

**Решение.** `SingBoxConfigBuilder.stackOf(variant)`: стек TUN берётся из варианта ядра,
по умолчанию `gvisor`; `mixed` и `system` остались вариантами для сравнения. gVisor есть
в сборке (`cmd/internal/build_libbox` добавляет тег `with_gvisor`), ядро пересобирать не нужно.
`CoreVariant` теперь: `1/5 · gVisor` (по умолчанию), `2/5 · mixed`, `3/5 · системный стек`,
`4/5 · DNS напрямую`, `5/5 · локальный прокси`. Старое сохранённое значение `BASE` больше
не существует и честно падает в gVisor (`CoreTuning.variant()`).

**Заодно.** Вариант «DNS напрямую» больше не пускает весь трафик мимо узла (прежний
`directResolver` менял и `route.final`) — теперь он трогает только detour DNS; мимо узла
пускает «Прямой режим». `CoreTrace.MAX_LINES` поднят с 160 до 400 строк: окна в 25 секунд
не хватало, интересные строки вытеснялись.

**Признак успеха в журнале:** `inbound connection from <ip>:<port>` → `inbound connection to
<ip>:443` → `outbound/vless[proxy]: outbound connection to <ip>:443`. Если и на gVisor
`inbound connection from` не появляется — TCP не доходит до TUN, и разбирать надо маршруты
и режим приложений, а не стек.

---

## 1h. Метрики на главном экране: пинг, загрузка, отдача (18.09.2026)

До этого три плитки показывали длинное тире: честно, но бесполезно. Теперь числа берутся
у самого ядра, а не выдумываются.

**Источник — локальный API ядра (Clash API).** В конфиг добавлено:

```json
"experimental": { "clash_api": { "external_controller": "127.0.0.1:19090", "secret": "<ключ>" } }
```

- `GET /traffic` — поток строк `{"up":N,"down":N}` в байтах в секунду (`experimental/clashapi`,
  `trafficcontrol.Manager`), на экран уходит в Мбит/с;
- `GET /proxies/proxy/delay?timeout=3000&url=https://www.gstatic.com/generate_204` —
  задержка проверки узла в миллисекундах. Важно: ядро **отклоняет открытый `http://`**
  в этом параметре (`getProxyDelay`: `if strings.HasPrefix(url, "http://") { url = "" }`)
  и при пустом URL берёт свой адрес по умолчанию — поэтому задан https-адрес явно.

Оба запроса делает `data/diagnostics/TunnelStats.kt` (класс из `AppContainer`): поток
скорости читается построчно и переподключается при разрыве, пинг опрашивается раз в 20 секунд.
Метрики собираются только при состоянии `Connected`; при выключенном туннеле поля сбрасываются
в `null`, и экран снова показывает тире.

**Безопасность петлевого API.** 127.0.0.1 на Android общий для всех приложений, поэтому API
закрыт ключом: `data/diagnostics/CoreApiToken.kt` генерирует 16 случайных байт один раз на
устройство, ключ уходит в конфиг ядра и в заголовке `Authorization: Bearer` от `TunnelStats`.
В журнал и в UI ключ не попадает.

**Локации стали информативнее.** Раньше протокол узла клался в поле «город» и пропадал у тех
узлов, где каталог знал город: «Нидерланды · Амстердам» без «VLESS · TCP · Reality».
Теперь у `VpnLocation` есть отдельные `protocol` и `limitation`, а `subtitle` собирает
«город · протокол · ограничение»; строки списка, карточка на главной и экраны ТВ показывают
одно и то же. Пометка «ядро не поддерживает XHTTP» снята: ядро из форка этот транспорт умеет,
а настройки CDN (`uplinkHTTPMethod`, размещение session/seq) доезжают до конфига (раздел 1i).
Поиск по списку ищет и по протоколу.

**Карточка «Локация» больше не врёт.** При поднятом туннеле она показывает узел, который
реально работает (`connectedLocationId` из снимка сервиса), а не выбранный в списке; если
выбор разошёлся с туннелем, добавляется «выбран другой узел» — смена локации на ходу ядро
не перезапускает.

---

## 1i. XHTTP и постоянная подпись (18.09.2026)

**XHTTP.** Владелец: «приложение должно быть всеядным», в подписке 4 узла VLESS и 4 XHTTP CDN.
Upstream sing-box XHTTP не умеет ни в 1.14.1, ни в v1.15.0-alpha.6 (в \`transport/\` только
v2ray/http/websocket/httpupgrade/grpc/quic), поэтому ядро собирается из форка
**Leadaxe/sing-box-lx** (ветка \`lx\`: база — upstream 1.14.1 + клиентский
\`transport/v2rayxhttp\`, тег \`with_xhttp\`). libbox-API форка совпадает с upstream,
платформенный слой не менялся.

- \`libbox.yml\`: \`singbox_repo=Leadaxe/sing-box-lx\`, \`singbox_ref=lx\`, NDK **r28c**
  (в CI форка именно он), кэш AAR v4 (в v3 лежало ядро без with_clash_api), плюс шаг, возвращающий \`with_clash_api\` в теги
  \`build_libbox\` (форк его не включает, а нам он нужен для метрик);
- \`SingBoxConfigBuilder.xhttp()\`: ссылка → \`transport\` по спецификации форка
  (\`URL_PARSING.md\`): \`path\` с обрезкой query-хвоста, \`host\`, \`mode\`,
  \`x_padding_bytes\`, \`no_grpc_header\`, placement-поля, \`uplink_*\`,
  \`sc_max_each_post_bytes\`/\`sc_min_posts_interval_ms\` («30.0» → «30»). Источники —
  плоские параметры и \`extra\` (URL-encoded JSON, приоритет у extra), ключи snake_case;
- \`PanelSubscription\`: настройки \`xhttpSettings\` из \`?format=xray\` переносятся
  в ссылку целиком — \`extra\` как есть плюс плоские поля по таблице «имена Xray → имена
  sing-box-lx». Без этого узел за CDN получал \`405 Method Not Allowed\`: ядро уходило на
  умолчания (uplink POST, session в пути), а панель требует \`uplinkHTTPMethod: GET\` и
  размещение session/seq в query;
- \`SingBoxConfigBuilder.vless()\`: строка \`encryption\` из ссылки переносится в outbound —
  пост-квантовое шифрование VLESS (\`mlkem768x25519plus…\`), которым панель закрывает CDN-узлы.
  Клиентскую часть форк умеет (SPEC 032), upstream sing-box — нет;
- обновление подписки: \`SubscriptionImporter\` запоминает ссылку-источник в защищённом
  хранилище, \`SubscriptionRepository.needsRefresh()\` следит за версией конвертера
  (\`CONVERTER_VERSION\`) и возрастом данных, \`StravoViewModel\` при старте тихо
  пересобирает узлы (успех и неудача — строкой в журнале ядра). Без этого правки панели
  (метод XHTTP, размещение session/seq) доходили до конфига только после повторного
  добавления подписки — именно на этом потерялся час с «405 Method Not Allowed»;
- в журнал добавлена строка «транспорт: …» — схема ушедшего в ядро конфига без хостов,
  путей и ключей;
- для XHTTP \`flow\` не выставляется: vision с ним несовместим;
- откат: вернуть в \`libbox.yml\` upstream-репозиторий и тег, поднять \`cache_version\`.

**Подпись.** Каждый прогон CI подписывал APK новым ключом: кэш \`~/.android/debug.keystore\`
в Actions не сохранялся (в списке кэшей его нет), у пяти сборок — пять разных сертификатов,
отсюда «невозможно обновить». Теперь в GitHub Secrets лежит постоянный keystore
(PKCS#12, CN=Stravo VPN, 30 лет), \`android.yml\` раскладывает его и падает без секрета,
а \`debug\` подписывается тем же ключом, что \`release\`. Переход требует одной переустановки:
установленная сборка подписана старым одноразовым ключом.

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
