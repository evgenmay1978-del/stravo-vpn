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
  крупные пакеты дробятся/теряются. Это последняя по времени правка, ещё не подтверждённая
  на устройстве.
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
1. **MTU туннеля 9000 → 1500** (уже в коде, не проверено на устройстве): при MTU 9000
   мелкие DNS-пакеты проходят, а TCP-сегменты (ClientHello ~500–1500 байт) теряются —
   ровно наблюдаемая картина. Проверить: журнал после правки должен показать
   `outbound/vless[proxy]: outbound connection to <ip>:443` и открывающиеся страницы.
2. **TCP не доходит до tun**: тогда в журнале при открытии страницы не появится ни
   `inbound connection`, ни `outbound connection`. Причина — маршруты TUN или режим
   приложений («Только выбранные»/«Все, кроме выбранных»). Проверять в браузере и смотреть,
   какие пакеты видит ядро.
3. **TCP доходит, но молча гибнет в gVisor-стеке** (`stack: "mixed"`): переключить вариант
   2/3 «системный стек» в настройках и повторить. В журнале появится `inbound connection`,
   но не `outbound connection`.
4. **Прокси-путь для stream-соединений**: если `inbound connection` и `outbound connection`
   есть, а ответов нет — смотреть XTLS Vision/Reality: `flow`, `fp`, `pbk`, `sid`, `sni`.
   Сверять с тем же узлом в рабочем клиенте.

## 3. Порядок действий

1. Поставить свежую сборку (раздел 4). Включить VPN, открыть пару сайтов в браузере.
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
