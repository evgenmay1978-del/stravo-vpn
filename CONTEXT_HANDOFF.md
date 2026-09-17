# STRAVO VPN — передача контекста

Документ для продолжения работы в новом чате. Секретов здесь нет: URL подписок, UUID и ключи
живут только у владельца и в Android Keystore.

- Репозиторий: `evgenmay1978-del/stravo-vpn`, ветка `main`.
- Приложение: `com.stravo.vpn`, minSdk 23, targetSdk 36, один APK для телефона и Android TV.
- Сборка: только GitHub Actions (`Android build`), локально не собираем.
- Последнее состояние: ядро sing-box (libbox) подключено, VPN **поднимается**, но **трафик не идёт** —
  это главная незакрытая задача.

---

## 1. Что сделано

### Issue #2 — форматы подписки (`domain/subscription/SubscriptionLinkParser.kt`, `data/subscription/PanelSubscription.kt`)
- ссылки-обёртки: `happ://`, `incy://`, `sub://`, `v2rayng://`, `clash://`, `sing-box://` и другие
  (вложенный адрес достаётся из query `url=`, из строки, из base64 и из процентного кодирования);
- адрес без схемы (`sub.example.com/x`) → https;
- тело подписки списком и base64;
- **JSON-форматы панелей**: `?format=xray` (массив конфигов Xray) и `?format=mihomo`
  (JSON-конфиг Clash) приводятся к share-ссылкам; `?format=links` (base64-список) работал и раньше;
- ошибка называет, что именно не распознано: «Схема «xxx» не распознана», «В строке нет схемы…»,
  «В ссылке «happ» нет адреса подписки».

Проверено на живой подписке владельца: `?format=xray` → 8 серверов, `?format=links` → 9 серверов
(один узел `naive+https` ядром не поддерживается и пропускается), `?format=mihomo` → 8 серверов.

### Issue #3 — ядро libbox (`engine/box/`)
- `StravoVpnService` : `VpnService()` + `PlatformInterface`: `openTun`, `protect`,
  `getInterfaces`, монитор сети по умолчанию, остальное — честные no-op;
- `SingBoxVpnEngine` : `VpnEngine` — состояние ядра в `observeState()`;
- `SingBoxConfigBuilder` — ссылка узла → JSON sing-box (VLESS TCP/WS/HTTP Upgrade/gRPC/QUIC + TLS/Reality,
  AnyTLS, Hysteria2, Trojan, Shadowsocks); XHTTP отдаёт честный Unsupported;
- `TunnelCore` — если нативные .so не загрузились, приложение честно об этом говорит;
- CI: `libbox.yml` стал reusable, `android.yml` и `release.yml` получают AAR артефактом
  (сборка не падает при промахе кэша);
- манифест: foreground service `dataSync`, `BIND_VPN_SERVICE`, разрешение VPN спрашивается в MainActivity.

**Два нативных падения найдены и исправлены** (оба роняли процесс целиком):
1. `startOrReloadService(config, null)` — Go разыменовывает `OverrideOptions` без проверки на nil.
   Теперь передаётся `OverrideOptions()`.
2. `getInterfaces()` отдавал адреса без длины префикса, а sing-box разбирает их через
   `netip.MustParsePrefix` (паника в Go). Теперь «адрес/префикс» из `interfaceAddresses`, IPv6 без scope.

### UI
- телефон **всегда** на кремовой бумаге (системная тёмная тема больше не включает графит);
- шапка со знаком S, названием и подзаголовком, медальон до 214dp, карточки-сводки, подпись под CTA;
- знак S в шапке — **тот же, что на иконке приложения** (`drawable-nodpi/s_mark.png`, вырезан из
  `docs/reference/app_icon.png`);
- плашка-уведомление гаснет сама и не перекрывает «Быстрое подключение»;
- локации: флаг в круглом бейдже, названия без протокола («Испания · VLESS» → «Испания»).

### Раздельный туннель (просьба владельца)
- Настройки → «Приложения через VPN»: режимы «Все», «Только выбранные», «Все, кроме выбранных»,
  поиск, иконки, системные приложения по желанию (`ui/mobile/AppsScreen.kt`);
- выбор хранится в настройках и уходит в ядро через `OverrideOptions`
  (`include_package`/`exclude_package`); пустой список — все приложения;
- в манифест добавлен `QUERY_ALL_PACKAGES`. **На устройстве ещё не проверялось.**

### Диагностика
- `CoreTrace` пишет шаги запуска ядра (переживает падение процесса),
  `StartupDiagnostics` читает `ApplicationExitInfo` — главный экран показывает одной строкой,
  что случилось в прошлый запуск (плюс последнее сообщение ядра без адресов и ключей);
- подписка переживает перезапуск (`SubscriptionRepository`, `commit()`).

---

## 2. Что не работает

**VPN поднимается, но трафик через него не идёт.** Симптомы:
- статус «ЗАЩИЩЕНО», в системном статус-баре есть значок VPN;
- при этом сайты (и заблокированные, и обычные) не открываются;
- проверка из другого приложения на устройстве: `https://api.ipify.org` вернул IP оператора,
  то есть трафик шёл мимо туннеля (в тот момент туннель, возможно, уже упал — тест нужно повторить
  при заведомо поднятом туннеле).

Отдельно: при обновлении APK поверх предыдущего подписка в интерфейсе оказывалась пустой —
нужно проверить, не теряются ли данные приложения при установке (сигнатура debug-ключа в CI
кэшируется, но факт требует проверки).

---

## 3. Что делать дальше (по порядку)

1. **Собрать и поставить последнюю сборку** (см. раздел 4). Подключиться к узлу.
2. **Посмотреть последнее сообщение ядра**: `CoreTrace.lastCoreMessage()` уже пишется в
   `writeDebugMessage` (адреса и ключи вырезаются) и показывается на главном экране следующего
   запуска. Там будет видно: не проходит Reality-рукопожатие, не резолвится DNS, нет маршрута и т.д.
   Если сообщение пустое — поднять `"log": {"level": "debug"}` в `SingBoxConfigBuilder.assemble()`.
3. **DNS**: в `openTun` уже добавлен запасной `1.1.1.1`, если ядро не отдало адреса
   (иначе Android идёт в DNS оператора, недоступный через туннель). Проверить, что на TUN реально
   ставится адрес из `options.getDNSServerAddress()` (у sing-box это hijack-адрес TUN).
4. **Проверить сам туннель**: при подключённом VPN из другого приложения запросить
   `https://1.1.1.1/cdn-cgi/trace` (без DNS) и `https://api.ipify.org` (с DNS).
   Если по IP работает, а по имени нет — это DNS. Если не работает ничего — outbound.
5. **Outbound**: сравнить наш JSON с рабочей ссылкой владельца (Happ/INCY/Karing работают на тех же
   узлах): `flow`, `fp`, `pbk`, `sid`, `sni`, `type`. Проверить, что `auto_detect_interface`
   не отдаёт ядру наш же tun (в `DefaultInterfaceMonitor` имена `tun*` отфильтрованы).
6. Проверить раздельный туннель на устройстве (список приложений, режимы) — код готов, не проверен.

---

## 4. Инфраструктура и как всё повторять

**Сборка**
- push в `main` → workflow `Android build` (job `core` собирает/достаёт `libbox.aar` из кэша,
  job `build` собирает debug и release APK).

**Скачать APK**
```
node ~/Stravo/tools/runs.cjs                 # список последних прогонов
node ~/Stravo/tools/dlapk.cjs <run_id>       # артефакт stravo-vpn-debug → ~/work/stravo-debug.zip
cd ~/work && rm -rf apkout && mkdir apkout && unzip -o -q stravo-debug.zip -d apkout
cp apkout/app-debug.apk /sdcard/Download/stravo-vpn-debug.apk
```
Токен GitHub лежит в `~/.config/dsh/github-token` — **никогда не коммитить и не печатать**.

**Установить на телефон**
«Мои файлы» → Загрузки → `stravo-vpn-debug.apk` → «Установщик пакетов» → «Только сейчас» →
в диалоге «Установить приложение?» кнопка справа (`pm install` не работает: нет прав).

**Проверить установленное**
```
unset LD_LIBRARY_PATH; P=$(pm path com.stravo.vpn | head -1 | cut -d: -f2); ls -la "$P"
```

**Логи**
`logcat` показывает только логи своего uid — логи ядра и краши STRAVO читать нельзя.
Поэтому в приложении есть `CoreTrace` + `StartupDiagnostics` (строка на главном экране).

**Полезные скрипты** (`~/Stravo/tools/`): `cipoll.cjs` (ждать прогон), `runs.cjs`, `dlapk.cjs`,
`joblog.cjs` (лог упавшего job), `check.cjs` (проверка скобок и CJK в исходниках),
`javap.cjs` (разбор классов AAR), `smark.cjs` (знак S из эталонной иконки).

**Исходники sing-box 1.14.1** для сверки API лежат в `~/work/sbsrc/sing-box-1.14.1`
(скачаны с GitHub), эталонный клиент — `SagerNet/sing-box-for-android` (`bg/BoxService.kt`,
`bg/VPNService.kt`) — по нему проверяем вызовы libbox.

---

## 5. Правила

- `AGENTS.md`: не логировать и не коммитить URL подписок, UUID, ключи, токены, полные конфиги;
  автотесты не добавлять; прод-серверы и платежи не трогать;
- стиль: бумага + графит, изумрудный — только функциональный акцент; никаких щитов, замков, пейзажей;
- `app/libs/*.aar` в git не хранится — его кладёт CI;
- если ядро или API не готовы — показывать честное состояние, не имитировать успех.

---

## 6. Карта изменённых файлов

```
app/src/main/java/com/stravo/vpn/
  data/AppContainer.kt                    DI: репозитории, CoreTrace, диагностика, ядро
  data/diagnostics/CoreTrace.kt           шаги ядра + последнее сообщение ядра
  data/diagnostics/StartupDiagnostics.kt  причина прошлого завершения (ApplicationExitInfo)
  data/settings/SettingsRepository.kt     настройки + режим раздельного туннеля и список пакетов
  data/subscription/PanelSubscription.kt  JSON панелей (Xray / Clash-mihomo) → share-ссылки
  data/subscription/SubscriptionImporter.kt  загрузка подписки, форматы тела, ошибки
  data/subscription/SubscriptionRepository.kt  подписка и узлы переживают перезапуск
  domain/subscription/SubscriptionLinkParser.kt  ссылки, обёртки, причины отказа
  domain/subscription/SubscriptionLocations.kt   названия локаций без протокола
  engine/box/StravoVpnService.kt          VpnService + PlatformInterface + диагностика
  engine/box/SingBoxVpnEngine.kt          VpnEngine поверх сервиса
  engine/box/SingBoxConfigBuilder.kt      ссылка узла → JSON sing-box
  engine/box/TunnelCore.kt                проверка загрузки нативных .so
  ui/components/SMark.kt                  знак S: PNG из иконки + упрощённый штрих
  ui/mobile/AppsScreen.kt                 раздельный туннель по приложениям
  ui/mobile/HomeScreen.kt                 главная по макету + строка диагностики
  ui/theme/StravoTheme.kt                 телефон всегда на бумаге
docs/IMPLEMENTATION.md                    разделы 1b–1e (ядро, форматы, грабли)
docs/QA_CHECKLIST.md                      чек-лист, включая форматы подписки
```

