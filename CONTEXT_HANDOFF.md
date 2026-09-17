# STRAVO VPN — передача контекста

Документ для продолжения работы в новом чате. Секретов здесь нет: URL подписок, UUID и ключи
живут только у владельца и в Android Keystore.

- Репозиторий: `evgenmay1978-del/stravo-vpn`, ветка `main`.
- Приложение: `com.stravo.vpn`, minSdk 23, targetSdk 36, один APK для телефона и Android TV.
- Сборка: только GitHub Actions (`Android build`), локально не собираем.
- Текущее состояние: найдена и исправлена причина «туннель поднимается, трафик не идёт».
  Идёт проверка на живом устройстве с живой подпиской.

---

## 1. Главная находка: кто на самом деле выпускает трафик

Ядро (libbox) на Android **не** умеет выбирать сеть самостоятельно. Диалог наружу всегда идёт
через параллельный диалер по интерфейсам, которые ядру отдал Java-слой:

- `experimental/libbox/service.go` — `UsePlatformNetworkInterfaces() == true` включает
  `NetworkStrategyDefault`: каждый dial идёт через `common/dialer/default_parallel_interface.go`;
- список сетей заполняется **только** из колбэка `startDefaultInterfaceMonitor`
  (`experimental/libbox/monitor.go` → `route/network.go`), других вызовов `UpdateInterfaces()` нет;
- диалеру нужен интерфейс, у которого индекс совпадает с тем, что сообщил монитор; иначе
  `no available network interface` — и падает **любое** соединение, включая DNS.

Отсюда все симптомы: TUN поднят, статус-бар показывает VPN, интерфейс пишет «ЗАЩИЩЕНО»,
а трафик не идёт. Плюс вторая ловушка: если индекс интерфейса не найден (в старом коде
подставлялся 0), ядро уходит в ветку привязки к интерфейсу (`SO_BINDTODEVICE`), которую
Android обычному приложению не разрешает.

## 2. Что сделано

### Issue #2 — форматы подписки (`domain/subscription/SubscriptionLinkParser.kt`, `data/subscription/PanelSubscription.kt`)
- ссылки-обёртки: `happ://`, `incy://`, `sub://`, `v2rayng://`, `clash://`, `sing-box://` и другие
  (вложенный адрес достаётся из query `url=`, из строки, из base64 и из процентного кодирования);
- адрес без схемы (`sub.example.com/x`) → https;
- тело подписки списком и base64;
- **JSON-форматы панелей**: `?format=xray` и `?format=mihomo` приводятся к share-ссылкам;
  `?format=links` (base64-список) работает и раньше;
- ошибка называет, что именно не распознано.

Проверено на живой подписке владельца: `?format=xray` → 8 серверов (один узел `naive+https`
ядром не поддерживается и пропускается).

### Issue #3 — ядро libbox (`engine/box/`)
- `StravoVpnService` : `VpnService()` + `PlatformInterface`, `SingBoxVpnEngine`, `SingBoxConfigBuilder`,
  `TunnelCore`;
- CI: `libbox.yml` reusable, `android.yml` и `release.yml` получают AAR артефактом;
- манифест: foreground service `dataSync`, `BIND_VPN_SERVICE`.

### Починка «трафик не идёт» (проверяется на устройстве)
- **монитор сети по умолчанию** (`StravoVpnService.DefaultInterfaceMonitor`) отдаёт ядру текущую
  сеть **сразу** при старте, повторяет попытки, пока свойства сети и интерфейс не станут видны,
  и честно сообщает «сети нет» (`""`, индекс `-1`), а не выдуманный индекс 0;
- **`getInterfaces()`** собирает только активные сети `ConnectivityManager` и заполняет имя, индекс,
  MTU, адреса, DNS, шлюз, тип и метрику; ошибка одной сети не обнуляет список;
- **флаги интерфейса** — константы Linux `IFF_*` (IFF_UP|IFF_RUNNING и т.д.), а не значения Go
  `net.Flags`: ядро переводит их в `net.Flags` своим `link_flags_unix.go`;
- **список приложений** больше не смешивает `addAllowedApplication` и `addDisallowedApplication`
  (Android это запрещает и исключение молча глоталось); своё приложение исключается первым;
- **openTun**: `setMetered(false)`, маршруты ядра на Android 13+ (как в эталонном клиенте),
  убран `setBlocking`, добавлен `sniff` — без него правило `hijack-dns` не разбирает DNS-пакеты;
- **диагностика**: `options.setDebug(true)` (без него ядро вообще не зовёт `writeDebugMessage`),
  журнал ядра на главном экране, выгрузка журнала в «Загрузки» (`stravo-core.log`),
  самопроверка туннеля (внешний адрес со стороны узла и контрольный запрос),
  «Прямой режим» и переключатель варианта сборки конфига в настройках;
- **авто-локация** подключается к первому узлу подписки (в подписке нет локации с id `auto`,
  раньше «Подключить» на ней честно отвечало «нет ключа узла»).

### UI
- телефон всегда на кремовой бумаге; шапка со знаком S; медальон до 214dp; карточки-сводки;
- знак S в шапке — тот же, что на иконке приложения (`drawable-nodpi/s_mark.png`);
- локации: флаг в бейдже, названия без протокола.

### Раздельный туннель
- Настройки → «Приложения через VPN»: «Все», «Только выбранные», «Все, кроме выбранных», поиск,
  иконки (`ui/mobile/AppsScreen.kt`); выбор уходит в ядро через `OverrideOptions`.
  На устройстве ещё не проверялся.

### Диагностика
- `CoreTrace` — шаги запуска и кольцевой журнал ядра (только в памяти процесса);
- `CoreLogReader` — хвост журнала самого ядра из `CrashReport-*.log` в `filesDir`;
- `CoreLogExporter` — выгрузка журнала в «Загрузки» через MediaStore;
- `TunnelProbe` — активная сеть (есть ли VPN-транспорт) и внешний адрес;
- `StartupDiagnostics` — причина прошлого завершения (`ApplicationExitInfo`);
- подписка переживает перезапуск (`SubscriptionRepository`, `commit()`).

## 3. Что делать дальше (по порядку)

1. **Поставить свежую сборку** (см. раздел 4) и подключиться к узлу.
2. **Прочитать журнал ядра** на главном экране (три последние строки) или взять файл
   `/sdcard/Download/stravo-core.log` (Настройки → «Сохранить журнал ядра»).
   Ключевые строки sing-box:
   - `updated default interface wlan0 (…) type wifi` — монитор сработал;
   - `no available network interface` — ядро не получило сеть от Java-слоя;
   - `dial … EPERM` — ушло в привязку к интерфейсу (значит индекс не найден);
   - строки inbound при висящих страницах — TUN ловит пакеты, проблема в outbound.
3. **Отличить «не работает туннель» от «не работает узел»**: Настройки → «Прямой режим
   (диагностика)» пускает трафик туннеля напрямую. Если сайты открываются — TUN, DNS и маршруты
   в порядке, дело в узле/ключе. Если нет — смотреть TUN и маршруты.
4. **Варианты ядра** (Настройки → «Вариант ядра»): 1/4 как есть, 2/4 без разбора протокола,
   3/4 системный стек, 4/4 DNS напрямую. Переключаются без пересборки, применяются при
   следующем подключении.
5. **Проверить раздельный туннель** на устройстве (список приложений, режимы).
6. **Проверить `?format=mihomo`** на живой подписке (раньше проверялся на локальных образцах).

## 4. Инфраструктура и как всё повторять

**Сборка**
- push в `main` → workflow `Android build` (job `core` достаёт `libbox.aar` из кэша,
  job `build` собирает debug и release APK). Один push = один прогон; несколько файлов
  удобнее коммитить одним изменением.

**Скачать APK**
```
node ~/Stravo/tools/runs.cjs                 # список последних прогонов
node ~/Stravo/tools/dlapk.cjs <run_id>       # артефакт stravo-vpn-debug → ~/work/stravo-debug.zip
cd ~/work && rm -rf apkout && mkdir apkout && unzip -o -q stravo-debug.zip -d apkout
cp apkout/app-debug.apk /sdcard/Download/stravo-vpn-debug.apk
```
Токен GitHub лежит в `~/.config/dsh/github-token` — **никогда не коммитить и не печатать**.

**Пуш без git**
`node ~/Stravo/tools/ghpush.cjs <каталог> <файл-с-сообщением>` — коммит через Contents API
(git в окружении нет). Перед пушем полезно `node ~/Stravo/tools/gitdiff.cjs <каталог>`:
показывает отличающиеся и новые файлы.

**Установить на телефон**
«Мои файлы» → Загрузки → `stravo-vpn-debug.apk` → «Установщик пакетов» → «Только сейчас» →
в диалоге «Установить приложение?» кнопка справа (`pm install` не работает: нет прав).
Debug-ключ в CI кэшируется не всегда: если система предложила «Удалить приложение?» —
keystore сменился, установка поверх невозможна, данные приложения при этом теряются
(подписку придётся добавить заново).

**Проверить установленное**
```
unset LD_LIBRARY_PATH; P=$(pm path com.stravo.vpn | head -1 | cut -d: -f2); ls -la "$P"
```

**Логи**
`logcat` показывает только логи своего uid — логи ядра и краши STRAVO читать нельзя.
Поэтому в приложении есть `CoreTrace`/`CoreLogReader` (главный экран) и выгрузка файла
в «Загрузки».

**Полезные скрипты** (`~/Stravo/tools/`): `cipoll.cjs` (ждать прогон), `runs.cjs`, `dlapk.cjs`,
`joblog.cjs`, `check.cjs` (скобки и CJK), `gitdiff.cjs`, `ghpush.cjs`, `clssig.cjs`
(сигнатуры классов AAR).

**Исходники для сверки**: sing-box 1.14.1 — `~/work/sbsrc/sing-box-1.14.1`, sing-tun 0.9.3 —
`~/work/singtun`, эталонный клиент `SagerNet/sing-box-for-android` — `~/work/sfa`
(`bg/VPNService.kt`, `bg/PlatformInterfaceWrapper.kt`, `bg/DefaultNetworkMonitor.kt`).

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
  engine/box/SingBoxConfigBuilder.kt      ссылка узла → JSON sing-box (+ варианты)
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
docs/QA_CHECKLIST.md                      чек-лист, включая форматы подписки
```
