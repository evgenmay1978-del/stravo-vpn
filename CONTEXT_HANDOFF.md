# STRAVO VPN — передача контекста

Документ для продолжения работы в новом чате. Секретов здесь нет: URL подписок, UUID и ключи
живут только у владельца и в Android Keystore.

- Репозиторий: `evgenmay1978-del/stravo-vpn`, ветка `main`.
- Приложение: `com.stravo.vpn`, minSdk 23, targetSdk 36, один APK для телефона и Android TV.
- Сборка: только GitHub Actions (`Android build`), локально не собираем.
- Текущее состояние: туннель поднимается, DNS через узел **работает**, обычный TCP-трафик
  приложений не идёт (страницы висят, телефон теряет интернет). Это последняя незакрытая задача.

---

## 0. Как читать журнал ядра (главный инструмент)

Системный `logcat` чужому uid недоступен, поэтому журнал живёт в приложении:

- главный экран → карточка «Журнал ядра» (последние строки);
- Настройки → «Сохранить журнал ядра» → файл `/sdcard/Download/stravo-core.log*.txt`;
- Настройки → «Скопировать журнал ядра» → буфер обмена;
- `CoreLogReader` дополнительно читает `filesDir/CrashReport-*.log`, куда libbox пишет
  stderr самого sing-box.

Что уже видно по журналам владельца (это факты, а не догадки):

```
шаг: TUN поднят: mtu 9000, адреса 2, маршрутов 0, адрес ядра 172.19.0.2,fdfe:dcba:9876::2, имя tun0
ядро: network: updated default interface rmnet_data0, index 289, type cellular, expensive
ядро: inbound/tun[tun-in]: inbound DNS packet from <ip>:…
ядро: outbound/vless[proxy]: outbound packet connection to <ip>:53
ядро: XtlsPadding … / Xtls Unpadding new block …
ядро: dns: exchanged <домен> NOERROR
```

То есть: TUN поднят, сеть по умолчанию отдана ядру, UDP/packet-соединения (DNS) через узел
проходят и **ответы приходят**. При этом в журнале нет ни одной строки
`outbound/vless[proxy]: outbound connection to <ip>:443` — то есть TCP-соединения от
приложений до outbound либо не доходят, либо умирают до логирования. Ошибок и WARN нет.

## 1. Что сделано

### Issue #2 — форматы подписки
`domain/subscription/SubscriptionLinkParser.kt`, `data/subscription/PanelSubscription.kt`:
обёртки `happ://`, `incy://`, `sub://`, `v2rayng://`, `clash://`, `sing-box://`; адрес без схемы;
тело списком и base64; JSON панелей `?format=xray` и `?format=mihomo` → share-ссылки; понятные
ошибки. Проверено на живой подписке: `?format=xray` → 8 серверов.

### Issue #3 — ядро libbox (`engine/box/`)
`StravoVpnService` : `VpnService()` + `PlatformInterface`, `SingBoxVpnEngine`,
`SingBoxConfigBuilder`, `TunnelCore`; CI собирает `libbox.aar` и подкладывает в `app/libs`.

### Платформенный слой (сверено с эталонным клиентом sing-box-for-android)
- **Монитор сети по умолчанию** — единственный источник сетей для ядра
  (`experimental/libbox/monitor.go` — единственный вызов `UpdateInterfaces()`): отдаёт
  текущую сеть сразу, повторяет попытки, пока свойства сети и интерфейс не станут видны.
- **Свой TUN никогда не отдаётся как сеть по умолчанию**: Android после `establish()` начинает
  показывать приложению наш же tun0, ядро отвечало `ERROR network: missing default interface`
  и переставало выпускать пакеты. Теперь сеть с VPN-транспортом/именем `tun*` отбрасывается,
  а если она всё же пришла — берётся активная сеть в обход туннеля (строка в журнале:
  «сеть по умолчанию (в обход туннеля)»).
- **`getInterfaces()`** собирает только активные сети `ConnectivityManager`: имя, индекс, MTU,
  адреса, DNS, шлюз, тип, метрика; ошибка одной сети не обнуляет список.
- **Флаги интерфейса — константы Linux `IFF_*`** (`IFF_UP=0x1`, `IFF_LOOPBACK=0x8`,
  `IFF_POINTOPOINT=0x10`, `IFF_RUNNING=0x40`, `IFF_MULTICAST=0x1000`): libbox переводит их в
  `net.Flags` своим `link_flags_unix.go`; значения Go `net.Flags` дают интерфейс без
  `IFF_RUNNING`.
- **Раздельный туннель**: нельзя смешивать `addAllowedApplication` и
  `addDisallowedApplication` (Android бросает исключение, оно молча глоталось); своё
  приложение исключается первым. Режим «Только выбранные» с пустым списком не пускает
  через VPN никого — это отдельная ловушка, проверять в чек-листе.
- **`findConnectionOwner`** через `ConnectivityManager.getConnectionOwnerUid` + пакеты uid.

### Конфиг ядра
- **`sniff` больше не поле inbound**: в sing-box 1.11+ поля `sniff`, `sniff_override_destination`,
  `domain_strategy` объявлены legacy, а с 1.13 проверка жёсткая —
  `initialize inbound[0]: legacy inbound fields are deprecated…`, ядро не стартует.
  Разбор включается правилом `{"action":"sniff"}` в `route.rules`, за ним
  `{"protocol":"dns","action":"hijack-dns"}`. Для tun — `address`/`route_address`
  (старые `inet4_address`/`inet4_route_address` удалены).
- **MTU туннеля 1500** вместо 9000 (значение sing-box по умолчанию): на мобильном канале
  крупные пакеты дробятся/теряются. Ещё не подтверждено на устройстве.
- **QUIC блокируется** правилом `network udp, port 443 -> outbound block-quic` — ровно так
  делает рабочий клиент на этих же узлах (конфиги панели). Без этого приложения висят на
  QUIC, вместо того чтобы сразу уйти на TCP. См. раздел 2b.
- Диагностические варианты (`CoreTuning`): 1/3 как есть, 2/3 системный стек, 3/3 DNS напрямую;
  «Прямой режим» пускает трафик туннеля без узла (отличить «не работает туннель» от
  «не работает узел»).

### UI, диагностика
Главный экран с журналом ядра и самопроверкой, настройки с диагностикой, выгрузка журнала
файлом и в буфер, `StartupDiagnostics`, авто-локация подключается к первому узлу подписки.

## 2. Что не работает и что уже проверено

**Симптом**: страницы не открываются, телефон теряет интернет, пока VPN включён.
При этом DNS через узел резолвится (см. журнал выше), а TCP-соединений в журнале нет.

Проверено и **исключено**:
- конфиг проходит `Libbox.checkConfig`, ядро стартует, TUN поднимается (`started at tun0`);
- сеть по умолчанию ядру отдаётся (`updated default interface rmnet_data0, index 289`);
- узел и ключ рабочие (те же серверы работают в INCY/Happ), Reality-рукопожатие проходит:
  видны `XtlsPadding` / `Xtls Unpadding new block`;
- DNS-ответы приходят через узел (`dns: exchanged … NOERROR`), то есть прокси-канал
  двунаправленный и не мёртвый;
- в конфиге нет неизвестных полей: `type`/`udp` DNS, `detour`, `default_domain_resolver`
  вида `{"server":…}`, `auto_detect_interface`, `stack`, `route.rules` — всё валидно для 1.14.1;
- правила `sniff`/`hijack-dns` — ровно то, что советует официальная миграция sing-box.

**Основные гипотезы, которые надо проверять дальше (по порядку)**:
1. **QUIC** (уже в коде, не проверено): приложения пробуют QUIC (UDP/443), он через узел
   не проходит (а через sing-box вообще не поддержан), и браузер висит вместо перехода на
   TCP. Рабочий клиент на этих же узлах блокирует QUIC первым правилом. Проверка: открыть
   сайт, в журнале должны появиться `outbound connection to <ip>:443` (TCP) и страница
   должна открыться.
2. **MTU 9000 → 1500** (уже в коде, не проверено): при MTU 9000 мелкие DNS-пакеты проходят,
   а TCP-сегменты (~500–1500 байт) теряются — тоже подходит под картину.
3. **TCP не доходит до tun**: в журнале при открытии страницы не появится ни
   `inbound connection`, ни `outbound connection`. Причина — маршруты TUN или режим
   приложений («Только выбранные»/«Все, кроме выбранных»); проверять режим «Все».
4. **TCP доходит, но гибнет в gVisor-стеке** (`stack: "mixed"`): переключить вариант
   «2/4 · системный стек» и повторить.
5. **Прокси-путь для stream-соединений**: если `inbound` и `outbound connection` есть, а
   ответов нет — сверять с узлом в рабочем клиенте `flow`, `fp`, `pbk`, `sid`, `sni`.

## 2a. Как устроены рабочие клиенты (изучено по исходникам)

**v2rayNG (2dust/v2rayNG, ветка `master`, Xray-core) — главный ориентир.**
- Архитектура: `CoreVpnService` поднимает TUN и **отдаёт файловый дескриптор ядру**
  (`CoreServiceManager.startCoreLoop(mInterface)`), в конфиге Xray — inbound `tun`
  с `"MTU": 1500` (`assets/v2ray_config_with_tun.json`), рядом локальный SOCKS:10808.
- TUN-билдер: `builder.setMtu(SettingsManager.getVpnMtu())`, `addAddress(ipv4Client, 30)`,
  `addRoute("0.0.0.0", 0)` (или список маршрутов, если включён обход LAN), IPv6 —
  `addAddress(ipv6Client, 126)` + `addRoute("::", 0)`, `setMetered(false)`
  (`service/CoreVpnService.kt`).
- DNS: адреса берутся из настроек и ставятся на билдер (`builder.addDnsServer`) — то есть
  DNS приложения уходит в туннель как обычный трафик, ядро его перехватывает.
- **QUIC блокируется**: в штатной маршрутизации первое правило —
  `{"remarks":"阻断udp443","outboundTag":"block","port":"443","network":"udp"}`
  (`assets/custom_routing_global`, `custom_routing_black`). Это подтверждает решение
  блокировать udp/443 и у нас.
- Сниффинг — в inbound через `sniffing.destOverride [http, tls, quic]` (в Xray поле
  своё, к sing-box не переносится).

**INCY / Happ.** Панель отдаёт `?format=xray` (JSON Xray) и `?format=links`
(base64-список share-ссылок); INCY на скриншоте показывает «VLESS · JSON · TCP · REALITY» —
то есть работает именно с Xray-конфигами. Их конфиг узла (сверено на живой подписке)
содержит: `inbounds` socks/http + `sniffing`, `outbounds` vless + freedom + **blackhole
`block-quic`**, `routing` с правилом `{"network":"udp","port":"443","outboundTag":"block-quic"}`
и затем всё в прокси. Happ — клиент с собственным ядром (Xray-совместимый), читает те же
share-ссылки и `extra`-параметры XHTTP; из официальной документации Happ
(dev-docs «examples-of-links-and-parameters») важно, что он поддерживает те же поля ссылок,
включая `mode`, `extra`, `alpn`, `fp`, `pbk`, `sid`.

**Общий вывод:** все три клиента (INCY, v2rayNG и панель) на одних и тех же узлах
**блокируют QUIC (udp/443)** и работают через Reality+Vision по TCP. Наш конфиг этого
правила не имел — оно добавлено (`block-quic` в outbounds и `route.rules`).
Второй общий момент — MTU 1500; у нас было 9000.

## 2b. XHTTP: что это и почему у нас «не поддерживается» (изучено)

Источники: официальный разбор Xray «XHTTP: Beyond REALITY»
(github.com/XTLS/Xray-core/discussions/4113), исходники Xray
`transport/internet/splithttp/config.go`, отчёт о клиентской поддержке в форке
sing-box-lx (`SPECS/TASKS/002-XHTTP_CLIENT_TRANSPORT/IMPLEMENTATION_REPORT.md`).

**Идея.** XHTTP — транспорт Xray, где канал разделён на два независимых HTTP-потока:

- **вверх**: клиент отправляет данные `POST /path/<sessionID>/<seq>`; в режиме
  `packet-up` каждый POST — самостоятельный кусок (сервер склеивает по `seq`, по
  умолчанию буферизует до 30), в `stream-up` вверх идёт одним долгим потоком с
  gRPC-подобной маскировкой заголовков;
- **вниз**: клиент открывает `GET /path/<sessionID>` и получает бесконечный ответ
  (SSE-подобный: `Content-Type: text/event-stream`, `X-Accel-Buffering: no`,
  `Cache-Control: no-store`).

Сессия связывается по случайному UUID в **path** (не в query — так меньше проблем с
посредниками), `seq` считается с нуля; сервер умеет переупорядочивать POST-ы. Смысл —
пройти через CDN и HTTP-посредники, которые кэшируют запрос целиком: вниз всегда идёт
«скачивание большого файла», вверх — пачка запросов.

**Режимы** (`mode`): `auto` (по умолчанию: packet-up на H1/H2, stream-one на H3),
`packet-up` (самый совместимый), `stream-up`, `stream-one` (по сути старый HTTP-транспорт).

**Параметры**: `path`, `host`, `mode`, `extra` (JSON со всеми тонкими настройками),
`alpn=h2|h3`, `security=tls|reality`, `sni`, `fp`, `pbk`, `sid`.
Внутри `extra` (и в Xray-конфиге): `sessionIDPlacement`/`sessionIDKey`/`sessionIDLength`
(path|query|header|cookie), `seqPlacement`/`seqKey`, `uplinkDataPlacement`
(body|auto|header|cookie) и `uplinkChunkSize`, `uplinkHTTPMethod`, `xPaddingBytes` +
`xPaddingObfsMode`/`xPaddingKey`/`xPaddingHeader`/`xPaddingPlacement`,
`scMaxEachPostBytes`, `scMinPostsIntervalMs`, `scMaxBufferedPosts`,
`scStreamUpServerSecs`, XMUX (`maxConcurrency`, `maxConnections`, `cMaxReuseTimes`,
`hMaxRequestTimes`, `hKeepAlivePeriod`) и серверные `serverMaxHeaderBytes`, `noSSEHeader`.

**Главное:** **sing-box 1.14.1 XHTTP не умеет вообще** — в `option/v2ray_transport.go`
перечислены только `http`, `ws`, `quic`, `grpc`, `httpupgrade`; типа `xhttp`/
`splithttp` нет ни в одном файле ядра. Поэтому `CoreConfig.Unsupported` для XHTTP-узлов —
честный отказ, а не заглушка.

**Варианты, если XHTTP-узлы нужны** (по росту цены):

1. **Использовать Reality+Vision узлы** — в подписке владельца их шесть
   (`VLESS · TCP · Reality`), они полностью поддержаны и соответствуют схеме 1.14.1.
2. **Собрать libbox из форка** с клиентским XHTTP: `Leadaxe/sing-box-lx` (ветка `lx`,
   build tag `with_xhttp`; реализованы 12 клиентских параметров + obfs, есть lx-build,
   тесты и отчёт) или `shtorm-7/sing-box-extended` (`transport/v2rayxhttp`).
   В workflow `libbox.yml` достаточно поменять `singbox_repo`/`singbox_ref`.
3. **Патчить upstream самим** — это отдельный транспорт (`transport/v2rayxhttp`),
   дни работы; спека, карта 16 полей и тесты есть в форке.

Чего делать не нужно: «эмулировать» XHTTP через `httpupgrade`/`http` — это другой
протокол, сервер его не поймёт.

## 3. Порядок действий

1. Поставить свежую сборку (раздел 4) и добавить подписку заново (обновление через
   установщик часто стирает данные приложения — см. про keystore в разделе 4).
   Включить VPN, открыть пару сайтов в браузере.
2. Настройки → «Сохранить журнал ядра» → прислать `stravo-core.log` (в нём нет адресов и ключей).
3. По журналу определить, на каком шаге рвётся TCP (гипотезы 2–4 выше), и править точечно.
4. Если TCP не доходит до tun — проверить режим приложений (должно быть «Все») и маршруты.
5. Если дело в стеке — переключить «Вариант ядра» на 2/3 и повторить без пересборки.
6. Никогда не проверять туннель «на живую» из этого же телефона, если по нему идёт сессия:
   при нерабочем туннеле связь пропадает — владельцу приходится выключать VPN вручную.

## 4. Инфраструктура

**Сборка**: push в `main` → workflow `Android build` (job `core` достаёт `libbox.aar` из кэша,
job `build` собирает debug и release APK).

**Скачать APK**
```
node ~/Stravo/tools/runs.cjs                 # список последних прогонов
node ~/Stravo/tools/dlapk.cjs <run_id>       # артефакт stravo-vpn-debug → ~/work/stravo-debug.zip
cd ~/work && rm -rf apkout && mkdir apkout && unzip -o -q stravo-debug.zip -d apkout
cp apkout/app-debug.apk /sdcard/Download/stravo-vpn-debug.apk
```
Токен GitHub лежит в `~/.config/dsh/github-token` — **никогда не коммитить и не печатать**.

**Пуш без git**: `node ~/Stravo/tools/ghpush.cjs <каталог> <файл-со-общением>`
(в окружении нет git; коммит через Contents API). Различия с origin — `gitdiff.cjs`.

**Установка на телефон**: «Мои файлы» → Загрузки → `stravo-vpn-debug.apk` → «Установщик
пакетов» → «Только сейчас» → «Установить». `pm install` не работает (нет прав).
Debug-ключ в CI кэшируется не всегда: при смене ключа установка поверх невозможна,
данные приложения теряются (подписку придётся добавить заново).

**Проверить установленное**
```
unset LD_LIBRARY_PATH; P=$(pm path com.stravo.vpn | head -1 | cut -d: -f2); ls -la "$P"
```

**Скрипты** (`~/Stravo/tools/`): `cipoll.cjs` (ждать прогон), `runs.cjs`, `dlapk.cjs`, `joblog.cjs`,
`check.cjs` (скобки и CJK), `gitdiff.cjs`, `ghpush.cjs`, `analyzlog.cjs`/`logtimeline.cjs`
(разбор журнала ядра), `clssig.cjs` (сигнатуры классов AAR).

**Исходники для сверки**: sing-box 1.14.1 — `~/work/sbsrc/sing-box-1.14.1` (там же `docs/`),
sing-tun 0.9.3 — `~/work/singtun`, эталон `SagerNet/sing-box-for-android` — `~/work/sfa`.

## 5. Правила

- `AGENTS.md`: не логировать и не коммитить URL подписок, UUID, ключи, токены, полные конфиги;
  автотесты не добавлять; прод-серверы и платежи не трогать;
- стиль: бумага + графит, изумрудный — только функциональный акцент;
- `app/libs/*.aar` в git не хранится — его кладёт CI;
- если ядро или API не готовы — показывать честное состояние, не имитировать успех.

## 6. Карта изменённых файлов

```
app/src/main/java/com/stravo/vpn/
  data/AppContainer.kt                    DI: репозитории, CoreTrace, диагностика, ядро
  data/diagnostics/CoreTrace.kt           шаги ядра + журнал в памяти
  data/diagnostics/CoreLogReader.kt       хвост журнала ядра из CrashReport-*.log
  data/diagnostics/CoreLogExporter.kt     выгрузка журнала в «Загрузки»
  data/diagnostics/Clipboard.kt           копирование журнала в буфер обмена
  data/diagnostics/StartupDiagnostics.kt  причина прошлого завершения (ApplicationExitInfo)
  data/diagnostics/TunnelProbe.kt         активная сеть и внешний адрес
  data/settings/SettingsRepository.kt     настройки, раздельный туннель, прямой режим
  data/subscription/PanelSubscription.kt  JSON панелей (Xray / Clash-mihomo) → share-ссылки
  data/subscription/SubscriptionImporter.kt  загрузка подписки, форматы тела, ошибки
  data/subscription/SubscriptionRepository.kt  подписка и узлы переживают перезапуск
  domain/subscription/SubscriptionLinkParser.kt  ссылки, обёртки, причины отказа
  domain/subscription/SubscriptionLocations.kt   названия локаций без протокола
  engine/box/StravoVpnService.kt          VpnService + PlatformInterface + диагностика
  engine/box/SingBoxVpnEngine.kt          VpnEngine поверх сервиса
  engine/box/SingBoxConfigBuilder.kt      ссылка узла → JSON sing-box (+ варианты, MTU 1500)
  engine/box/CoreTuning.kt                диагностические варианты сборки конфига
  engine/box/TunnelCore.kt                проверка загрузки нативных .so
  ui/components/CoreLogCard.kt            журнал ядра на главном экране
  ui/components/SMark.kt                  знак S: PNG из иконки + упрощённый штрих
  ui/mobile/AppsScreen.kt                 раздельный туннель по приложениям
  ui/mobile/HomeScreen.kt                 главная по макету + журнал + самопроверка
  ui/mobile/SettingsScreen.kt             настройки + диагностика ядра
  ui/state/HomeUiState.kt                 состояние главного экрана, авто-локация
  ui/state/StravoViewModel.kt             события, проба, выгрузка журнала
  ui/theme/StravoTheme.kt                 телефон всегда на бумаге
docs/IMPLEMENTATION.md                    разделы 1b–1e (ядро, форматы, грабли)
docs/QA_CHECKLIST.md                      чек-лист: форматы подписки и реальный трафик
```
