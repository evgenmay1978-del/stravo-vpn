# STRAVO VPN — передача контекста

Документ для продолжения работы в новом чате. Секретов здесь нет: URL подписок, UUID и ключи
живут только у владельца и в Android Keystore.

- Репозиторий: `evgenmay1978-del/stravo-vpn`, ветка `main`.
- Приложение: `com.stravo.vpn`, minSdk 23, targetSdk 36, один APK для телефона и Android TV.
- Сборка: только GitHub Actions (`Android build`), локально не собираем.
- Состояние на 18.09.2026: **Reality и TCP закрыты и подтверждены владельцем** — сначала
  отпечаток uTLS (раздел 1), затем стек TUN `gvisor` (раздел 1a). Поверх этого сделаны
  метрики главного экрана (раздел 1b) и понятные подписи локаций.
- Открыто: проверить на устройстве новые плитки «Пинг / Загрузка / Отдача» и пометку
  «ядро не поддерживает XHTTP»; отдельно решить, что делать с XHTTP-узлами CDN
  (ядро 1.14.1 их не умеет — раздел 2b).

---

## 0. Как читать журнал ядра (главный инструмент)

Системный `logcat` чужому uid недоступен, поэтому журнал живёт в приложении:

- главный экран → карточка «Журнал ядра» (последние строки);
- Настройки → «Сохранить журнал ядра» → файл `/sdcard/Download/stravo-core.log*.txt`;
- Настройки → «Скопировать журнал ядра» → буфер обмена (оттуда читается `android_clipboard`);
- `CoreLogReader` дополнительно читает `filesDir/CrashReport-*.log`, куда libbox пишет
  stderr самого sing-box.

Что видно в журнале, когда всё плохо (лог владельца, 18.09.2026 06:17):

```
inbound/tun[tun-in]: inbound DNS packet from <ip>:…
router: found package name: <приложение>
dns: exchange <домен>. IN A
outbound/vless[proxy]: outbound packet connection to <ip>:53
ERROR router: process DNS packet: dial UDP connection: reality verification failed
```

`reality verification failed` — это не «нет сети» и не «узел лежит»: TCP до узла доходит,
TLS-рукопожатие начинается, но REALITY-подписи в сертификате нет (сервер увёл нас на
сайт-заглушку). Причина — в нашем ClientHello (раздел 1).

Когда всё хорошо, в журнале есть:

```
шаг: TUN поднят: mtu 1500, адреса 2, маршрутов 2, адрес ядра …, имя tun0
ядро: network: updated default interface <iface>, index <n>, type cellular
ядро: XtlsPadding … / Xtls Unpadding new block …
ядро: dns: exchanged <домен> NOERROR
ядро: outbound/vless[proxy]: outbound connection to <ip>:443
```

## 1. Причина «VPN не работает»: REALITY (исправлено 18.09.2026, подтверждено владельцем)

### Что случилось

08.09.2026 в серверной библиотеке REALITY (`xtls/reality`) появился коммит `8cdf7bf9`
«REALITY protocol: Reject outdated/strange Client Hello that doesn't have X25519MLKEM768
before optional X25519». Он вошёл в Xray-core v26.9.8 и v26.9.9 (обе — 08.09.2026).
С этого момента сервер требует, чтобы в ClientHello была гибридная пост-квантовая доля
ключа **X25519MLKEM768 (0x11ec)** и шла **перед** обычной X25519 (0x001d):

```go
for _, keyShare := range hs.clientHello.keyShares {
    if keyShare.group == X25519MLKEM768 && len(keyShare.data) == mlkem.EncapsulationKeySize768+32 {
        peerPub2 = keyShare.data[mlkem.EncapsulationKeySize768:]
        continue
    }
    if keyShare.group == X25519 && len(keyShare.data) == 32 {
        peerPub = keyShare.data
        break
    }
}
if peerPub2 == nil {
    break // reject outdated/strange Client Hello that doesn't have X25519MLKEM768 …
}
```

Если доли нет, сервер не считает клиента REALITY-клиентом: он молча уводит соединение на
настоящий сайт-заглушку (`dest`) и ретранслирует ответ. Клиент видит настоящий сертификат,
не находит в нём REALITY-подпись (HMAC-SHA512 по ed25519-ключу, посчитанный на общем
секрете) и падает с `reality verification failed`. Падает самый первый шаг — установка TLS,
поэтому через узел не идёт вообще ничего, включая DNS.

### Почему это случилось именно у нас

Ядро — sing-box 1.14.1 с `metacubex/utls v1.8.7` (`common/tls/reality_client.go`
и `common/tls/utls_client.go`). Гибридную долю X25519MLKEM768 в этой версии uTLS несёт
**только отпечаток `chrome`** (`HelloChrome_133`: GREASE, X25519MLKEM768, X25519).
Остальные отпечатки, которые понимает sing-box, отправляют только обычную X25519:

| `fp` из ссылки | ClientHello (uTLS 1.8.7) | новый сервер |
| --- | --- | --- |
| `chrome`, пусто | GREASE, **X25519MLKEM768**, X25519 | принимает |
| `firefox` | X25519, P256 | отбрасывает |
| `safari` | GREASE, X25519 | отбрасывает |
| `ios` | GREASE, X25519 | отбрасывает |
| `edge` | X25519, P256 | отбрасывает |
| `android`, `360`, `qq` | без PQ | отбрасывает |
| `random` | один из пяти (шанс 1/5) | как повезёт |
| `randomized` | свой набор | отбрасывает |

До 08.09.2026 отпечаток на приём не влиял — работал любой. Клиенты на Xray (INCY, Happ,
v2rayNG) отправляют PQ-долю всегда (`if ecdhe == nil { ecdhe = KeyShareStates.MlkemEcdhe }`),
поэтому на тех же узлах у них всё работает. Это и был главный признак: **узел жив, виноват
наш ClientHello.**

### Что исправлено в коде

`app/src/main/java/com/stravo/vpn/engine/box/SingBoxConfigBuilder.kt`, `applyTls`:

- для REALITY отпечаток из ссылки больше не берётся — всегда ставится `chrome`
  (константа `REALITY_FINGERPRINT`, там же объяснение почему);
- для не-REALITY TLS (`security=tls`) отпечаток из ссылки уважается как раньше;
- побочный эффект: ссылка без `fp` больше не даёт `uTLS is required by reality client`
  (раньше блок `utls` в конфиг не попадал вовсе, и ядро отказывалось строить outbound).

`StravoVpnService.openTun`: в строке «TUN поднят» теперь считается реальное число маршрутов,
ушедших в `Builder` (пустой список `route_address` означает «весь трафик» — 0.0.0.0/0 и ::/0,
и в журнале это 2, а не 0; прежняя формулировка путала при разборе).

### Подтверждение от владельца (журнал 06:37, после установки сборки с правкой)

```
TUN поднят: mtu 1500, адреса 2, маршрутов 2, имя tun
outbound/vless[proxy]: XtlsPadding 50 235 0
outbound/vless[proxy]: Xtls Unpadding new block 21 16 padding 115 0
dns: exchanged www.google.com NOERROR 232
dns: exchanged wapmixx.ru NOERROR 600
```
Ни одной строки `reality verification failed`. Рукопожатие проходит, DNS идёт через узел.

## 1a. Вторая причина: TCP не доходит до обработчика — виноват стек TUN (правка 18.09)

**Симптом.** После починки REALITY страницы всё ещё не открываются. В журнале (06:44,
окно 25 секунд, YouTube и Kimi в работе):

```
34 × router: pre-match[0] => sniff                 ← судятся новые потоки
 5 × inbound/tun[tun-in]: inbound packet connection from/to   ← это UDP, он доходит
 6 × router: sniffed packet protocol: quic … => route(block-quic)   ← QUIC блокируется как задумано
 0 × inbound connection from / inbound connection to           ← TCP-соединений НЕТ НИ ОДНОГО
 0 × outbound connection to <ip>:443
```

**Что это значит.** `pre-match[0] => sniff` для TCP печатает `Router.PreMatch`
(`route/route.go`): при не-UDP потоке правило `sniff` сразу возвращает `PreMatchContinue`.
Дальше `ForwardDispatcher.judgeAndInstall` ставит вердикт `ActionAccept` и **не** забирает
пакет — он уходит в `Mixed.processIPv4` → `System.processIPv4TCP`, то есть в **системный
стек**: пакет переписывается (src = второй адрес TUN, dst = адрес TUN + NAT-порт) и
возвращается в TUN, чтобы ядро отдало его слушающему сокету, откуда sing-box создаёт
`inbound connection`. Именно этого шага и не происходит: SYN-ы судятся и исчезают,
`inbound connection from` не появляется ни разу. UDP при этом идёт другим путём — в `mixed`
он инжектится в gVisor и работает (DNS, QUIC-блокировка).

Проверено по исходникам: `docs/configuration/inbound/tun.md` — `mixed` = «Mixed `system` TCP
stack and `gvisor` UDP stack»; `sing-tun/stack.go` → `NewStack("mixed") = NewMixed`,
`stack_mixed.go: processIPv4` → TCP в `System.processIPv4TCP`, UDP в gVisor.
Клиенты, которые на этих же узлах работают (INCY, Happ, v2rayNG на Xray), используют
tun с gVisor — конфиг v2rayNG (`assets/v2ray_config_with_tun.json`) поля `stack` не задаёт,
то есть берётся gVisor по умолчанию.

**Что исправлено.**

- `SingBoxConfigBuilder.stackOf(variant)` — стек TUN теперь берётся из варианта ядра,
  по умолчанию **`gvisor`** (TCP и UDP через gVisor: соединение сразу попадает в
  `NewConnectionEx` и уходит в outbound). `mixed` и `system` остались как варианты для
  сравнения; `mv`/gVisor в сборке libbox есть (`cmd/internal/build_libbox` кладёт тег
  `with_gvisor`), так что пересобирать ядро не нужно.
- `CoreVariant` переразмечен на 5 вариантов: `1/5 · gVisor` (по умолчанию), `2/5 · mixed`
  (как было), `3/5 · системный стек`, `4/5 · DNS напрямую`, `5/5 · локальный прокси`.
  Переключение — в настройках, без новой сборки; сохранённый старый вариант не находится
  (`BASE` переименован) и честно падает в gVisor.
- Вариант «DNS напрямую» больше не пускает весь трафик мимо узла (это делал прежний
  `directResolver`): теперь он меняет только detour DNS, а «Прямой режим» — по-прежнему
  весь трафик.
- `CoreTrace.MAX_LINES`: 160 → 400 строк. Окно журнала в 25 секунд оказалось мало для
  разбора: половина интересного вытеснялась.

**Что смотреть в следующем журнале (признак успеха):**
`inbound connection from <ip>:<port>` → `inbound connection to <ip>:443` →
`outbound/vless[proxy]: outbound connection to <ip>:443` → страницы открываются.
Если и с gVisor не появится `inbound connection from` — значит TCP не доходит до TUN
(разбирать маршруты и режим приложений), а не стек.

## 1b. Метрики главного экрана и понятные локации (18.09.2026)

**Пинг / Загрузка / Отдача.** Три плитки раньше всегда показывали тире. Теперь числа берутся
у ядра через локальный API (Clash API на `127.0.0.1:19090`, ключ в `experimental.clash_api`):

- `GET /traffic` — поток `{"up":N,"down":N}` (байт/с) → Мбит/с на экране;
- `GET /proxies/proxy/delay?timeout=3000&url=https://www.gstatic.com/generate_204` — пинг, мс.
  Открытый `http://` в параметре `url` ядро отклоняет, поэтому адрес задан https и явно.

Собирает `data/diagnostics/TunnelStats.kt`, ключ доступа — `data/diagnostics/CoreApiToken.kt`
(16 случайных байт, хранятся в SharedPreferences, в журнал и UI не попадают). Сбор идёт
только при `Connected`; при выключенном туннеле поля сбрасываются в null и экран честно
показывает тире.

**Локации.** Протокол узла раньше клался в поле «город» и пропадал у узлов, где каталог знал
город («Нидерланды · Амстердам» без «VLESS · TCP · Reality»). Теперь у `VpnLocation` есть
`protocol` и `limitation`, а `subtitle` собирает «город · протокол · ограничение»; так же
выглядят список, карточка на главной и ТВ-экраны. Узлы XHTTP помечены «ядро не поддерживает
XHTTP» — честно, а не как рабочие. Поиск в списке ищет и по протоколу.

**Карточка «Локация».** При поднятом туннеле показывает узел, который реально работает
(`connectedLocationId` из снимка сервиса), а не выбранный в списке; при расхождении
добавляется «выбран другой узел» — смена локации на ходу ядро не перезапускает.

## 2. Что проверено и **не** является причиной

- **TUN и маршруты в порядке.** `openTun` при `auto_route` и пустом списке `route_address`
  ставит `0.0.0.0/0` (и `::/0`) плюс адреса DNS из опций ядра — поэтому DNS-пакеты и попадали
  в туннель. Строка «маршрутов 0» в старых журналах считала только явные маршруты конфига;
  теперь это исправлено.
- **Сеть по умолчанию ядру отдаётся** (`updated default interface rmnet_data0`), свой `tun0`
  отбрасывается — иначе ядро уходило бы в себя.
- **MTU 1500 и блокировка QUIC** (`network udp, port 443 -> outbound block-quic`) совпадают
  с рабочими клиентами на этих узлах (v2rayNG, конфиги панели) и оставлены. Блокировка QUIC
  в журнале владельца работает как задумано (`sniffed packet protocol: quic … => route(block-quic)`),
  ошибка `connection: listen packet connection … block-quic: operation not permitted` —
  шум самого block-outbound, пакеты всё равно не выпускаются.
- **Системный стек TUN (не путать с «сетью по умолчанию»)** — не «не проверено», а измерено:
  TCP-соединений через него не появляется вовсе (раздел 1a).
- **Конфиг валиден для 1.14.1**: `sniff` и `hijack-dns` — правилами в `route.rules`,
  `address`/`route_address` вместо legacy-полей, `default_domain_resolver` вида `{"server":…}`.
- **XHTTP ядро 1.14.1 не умеет** — это отдельный честный отказ `CoreConfig.Unsupported`,
  а не заглушка (раздел 2b).

## 2a. Как устроены рабочие клиенты (изучено по исходникам)

**v2rayNG (2dust/v2rayNG, Xray-core) — главный ориентир.**
- `CoreVpnService` поднимает TUN и **отдаёт файловый дескриптор ядру**
  (`CoreServiceManager.startCoreLoop(mInterface)`), в конфиге Xray — inbound `tun`
  с `"MTU": 1500`, рядом локальный SOCKS:10808.
- TUN-билдер: `setMtu(...)`, `addAddress(ipv4Client, 30)`, `addRoute("0.0.0.0", 0)`,
  IPv6 — `addAddress(ipv6Client, 126)` + `addRoute("::", 0)`, `setMetered(false)`.
- DNS-адреса ставятся на билдер (`builder.addDnsServer`) — DNS приложения уходит в туннель
  как обычный трафик, ядро его перехватывает.
- **QUIC блокируется**: в штатной маршрутизации первое правило —
  `{"remarks":"阻断udp443","outboundTag":"block","port":"443","network":"udp"}`.
- REALITY: клиент Xray всегда отправляет PQ-долю (`Ecdhe`, иначе `MlkemEcdhe`) — см. раздел 1.

**INCY / Happ.** Панель отдаёт `?format=xray` и `?format=links`; INCY показывает
«VLESS · JSON · TCP · REALITY». Конфиг узла содержит `outbounds` vless + freedom +
blackhole `block-quic` и правило `{"network":"udp","port":"443","outboundTag":"block-quic"}`.
Happ читает те же share-ссылки и `extra`-параметры XHTTP.

**Общий вывод:** на этих узлах все рабочие клиенты блокируют QUIC (udp/443), ходят по TCP
и **отправляют X25519MLKEM768**. У нас теперь есть и то, и другое.

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
посредниками), `seq` считается с нуля; сервер умеет переупорядочивать POST-ы.

**Режимы** (`mode`): `auto`, `packet-up` (самый совместимый), `stream-up`, `stream-one`.

**Параметры**: `path`, `host`, `mode`, `extra` (JSON со всеми тонкими настройками),
`alpn=h2|h3`, `security=tls|reality`, `sni`, `fp`, `pbk`, `sid`.
Внутри `extra`: `sessionIDPlacement`/`sessionIDKey`/`sessionIDLength`,
`seqPlacement`/`seqKey`, `uplinkDataPlacement` и `uplinkChunkSize`, `uplinkHTTPMethod`,
`xPaddingBytes` + `xPaddingObfsMode`/`xPaddingKey`/`xPaddingHeader`/`xPaddingPlacement`,
`scMaxEachPostBytes`, `scMinPostsIntervalMs`, `scMaxBufferedPosts`, `scStreamUpServerSecs`,
XMUX (`maxConcurrency`, `maxConnections`, `cMaxReuseTimes`, `hMaxRequestTimes`,
`hKeepAlivePeriod`) и серверные `serverMaxHeaderBytes`, `noSSEHeader`.

**Главное:** **sing-box 1.14.1 XHTTP не умеет вообще** — в `option/v2ray_transport.go`
перечислены только `http`, `ws`, `quic`, `grpc`, `httpupgrade`; типа `xhttp`/
`splithttp` нет ни в одном файле ядра. Поэтому `CoreConfig.Unsupported` для XHTTP-узлов —
честный отказ, а не заглушка.

**Варианты, если XHTTP-узлы нужны** (по росту цены):

1. **Использовать Reality+Vision узлы** — в подписке владельца их шесть
   (`VLESS · TCP · Reality`), они полностью поддержаны.
2. **Собрать libbox из форка** с клиентским XHTTP: `Leadaxe/sing-box-lx` (ветка `lx`,
   build tag `with_xhttp`) или `shtorm-7/sing-box-extended` (`transport/v2rayxhttp`).
   В workflow `libbox.yml` достаточно поменять `singbox_repo`/`singbox_ref`.
3. **Патчить upstream самим** — это отдельный транспорт, дни работы.

Чего делать не нужно: «эмулировать» XHTTP через `httpupgrade`/`http` — это другой
протокол, сервер его не поймёт.

## 3. Порядок действий

1. Собрать и поставить свежую сборку (раздел 4), добавить подписку (установка APK часто
   стирает данные приложения — см. про keystore).
2. Включить VPN, открыть пару сайтов.
3. Настройки → «Сохранить журнал ядра» (файл `stravo-core.log` в «Загрузках»; буфер обмена
   Android фоновому приложению не отдаёт, поэтому журнал забираем файлом).
4. Смотреть по порядку:
   - `XtlsPadding` / `dns: exchanged … NOERROR` — REALITY и узел живы (уже подтверждено);
   - `inbound connection from <ip>:<port>` → `outbound connection to <ip>:443` — TCP доходит
     до обработчика и уходит в узел (это и проверяет правку стека, раздел 1a);
   - если TCP-строк нет вовсе — переключить «Вариант ядра» в настройках (2/5 mixed, 3/5
     системный) и повторить: правка стека проверяется без новой сборки.
5. Если с gVisor `inbound connection from` появится, а ответов нет — сверять с панелью
   `pbk`, `sid`, `sni`, `flow`; клиенты Xray на тех же узлах работают.
6. Если и `inbound connection` нет — разбирать маршруты и режим приложений (должно быть
   «Все», см. раздел 2), а не ядро.
7. Никогда не проверять туннель «на живую» из этого же телефона, если по нему идёт сессия:
   при нерабочем туннеле связь пропадает — владельцу приходится выключать VPN вручную.

## 4. Инфраструктура

**Сборка**: push в `main` → workflow `Android build` (job `core` достаёт `libbox.aar` из
кэша, job `build` собирает debug и release APK). Ядро — pinned `SagerNet/sing-box v1.14.1`,
ключ кэша AAR `libbox-aar-v1.14.1-go1.26.8-gomobilev0.1.13-android-arm64-android-arm-v2`.

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
Release-сборка подписывается тем же debug-ключом (если владелец не задал свой keystore),
поэтому debug и release взаимозаменяемы; при промахе кэша ключа установка поверх невозможна
и данные приложения теряются (подписку придётся добавить заново).

**Проверить установленное**
```
unset LD_LIBRARY_PATH; P=$(pm path com.stravo.vpn | head -1 | cut -d: -f2); ls -la "$P"
```

**Скрипты** (`~/Stravo/tools/`): `cipoll.cjs` (ждать прогон), `runs.cjs`, `dlapk.cjs`, `joblog.cjs`,
`check.cjs` (скобки и CJK), `gitdiff.cjs`, `ghpush.cjs`, `analyzlog.cjs`/`logtimeline.cjs`
(разбор журнала ядра), `clssig.cjs` (сигнатуры классов AAR).

**Исходники для сверки**: sing-box 1.14.1 — `~/work/sbsrc/sing-box-1.14.1`, sing-tun 0.9.3 —
`~/work/singtun`, эталон `SagerNet/sing-box-for-android` — `~/work/sfa`,
разбор REALITY (сервер и клиент) — `~/work/vpndiag/` (`rel/REALITY-main`, `utls/utls-1.8.7`).

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
  engine/box/SingBoxConfigBuilder.kt      ссылка узла → JSON sing-box (+ REALITY: fp chrome)
  data/diagnostics/TunnelStats.kt         пинг и скорость из петлевого API ядра
  data/diagnostics/CoreApiToken.kt        ключ доступа к петлевому API ядра
  engine/box/CoreTuning.kt                варианты ядра: gVisor / mixed / системный / DNS / SOCKS
  engine/box/TunnelCore.kt                проверка загрузки нативных .so
  ui/components/CoreLogCard.kt            журнал ядра на главном экране
  ui/components/SMark.kt                  знак S: PNG из иконки + упрощённый штрих
  ui/mobile/AppsScreen.kt                 раздельный туннель по приложениям
  ui/mobile/HomeScreen.kt                 главная по макету + журнал + самопроверка
  ui/mobile/SettingsScreen.kt             настройки + диагностика ядра
  ui/state/HomeUiState.kt                 состояние главного экрана, авто-локация
  ui/state/StravoViewModel.kt             события, проба, выгрузка журнала
  ui/theme/StravoTheme.kt                 телефон всегда на бумаге
docs/IMPLEMENTATION.md                    разделы 1b–1f (ядро, форматы, грабли, REALITY)
docs/QA_CHECKLIST.md                      чек-лист: форматы подписки и реальный трафик
```
