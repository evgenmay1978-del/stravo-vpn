# STRAVO VPN

Одно универсальное Android-приложение и один APK для **телефона** и **Android TV**.
Стиль: бумага и графит, карандашные чертёжные линии, единственный функциональный акцент — изумрудный.

![Экраны](design-kit/reference/screens_reference.png)

---

## Что уже есть

**Телефон:** Главная (медальон подключения, локация, профиль, режим, статистика, «Быстрое подключение»),
Выбор локации (фильтр и поиск, серверы из подписки), Профиль (подписка и сервисные действия),
**Добавить подписку** (ссылка подписки, ключ, QR, вставка из буфера), Подключить ТВ, Настройки.
Нижняя навигация: Главная · Локации · Профиль · Настройки.

**Android TV:** боковой рельс (Главная, Локации, Профиль, Подключить телефон, Настройки),
главный экран с медальоном и полосой протоколов (Авто, VLESS, Hysteria2, AnyTLS, WebRTC),
экран переноса подписки с QR, обратным отсчётом и кнопкой «Обновить QR-код»,
экран успешного переноса. Весь интерфейс проходится только D-pad, фокус всегда виден.

---

## Честный статус (важно)

| Компонент | Состояние |
|---|---|
| UI телефона и TV, дизайн-система, навигация, настройки | готово |
| Кнопка «Быстрое подключение» → Telegram-бот | готово (tg:// → https → копирование ссылки) |
| QR-перенос подписки phone → TV (клиентская часть, TTL, одноразовый токен) | готово |
| **Подписка**: импорт по ссылке, ключу (VLESS, AnyTLS, Hysteria2, Trojan, Shadowsocks) и QR | готово |
| Разбор узлов подписки: протокол, транспорт (включая **XHTTP**), Reality/TLS — без хостов и ключей в UI | готово |
| Хранение секретов: AES-256-GCM, ключ в Android Keystore, без бэкапа и логов | готово |
| **Ядро туннеля (VpnService / протоколы)** | **подключено**: sing-box (libbox) — `StravoVpnService` + TUN, VLESS/AnyTLS/Hysteria2/Trojan/Shadowsocks. Транспорт XHTTP ядро этой сборки не умеет и честно об этом сообщает |
| **Pairing-API на стороне сервиса** | **отсутствует** — `PairingBackend` объявлен, заглушка возвращает «не настроено» |

Подключение **не имитируется таймером**: состояние приходит только от ядра (libbox), а если
ядро не поднялось — на экране понятная причина, а не «подключено». Перенос подписки не показывает
фальшивый успех: QR создаётся локально и ведёт в бота, но статус подтверждения обязан прийти с сервера.

Контракты описаны в [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md).

---

## Подписка и протоколы

Подписка добавляется на телефоне: **Профиль → Добавить подписку**. Принимаются:

- ссылка подписки https://… (тело — список ссылок или base64; заголовок subscription-userinfo
  даёт дату окончания, profile-title — название плана);
- одиночный ключ vless://, anytls://, hysteria2://, trojan://, ss://;
- ссылка-обёртка клиента: happ://add/…, incy://…, sub://…, v2rayng://…, clash://…, sing-box://… —
  вложенный адрес достаётся и из строки, и из base64;
- адрес без схемы (голый домен sub.example.com/x) и тело подписки списком;
- форматы панелей: base64-список ссылок, JSON-массив конфигов Xray (?format=xray),
  JSON-конфиг Clash/mihomo (?format=mihomo) — из них достаются vless/trojan/shadowsocks-узлы;
- тот же ключ из QR-кода (Профиль → Добавить подписку → Сканировать QR).

Каталог протоколов — domain/model/VpnProtocol.kt: VLESS (TCP, WebSocket, HTTP Upgrade,
**XHTTP**, gRPC, QUIC), AnyTLS, Hysteria2, Trojan, Shadowsocks и служебный WebRTC.
Транспорт и защита (TLS/Reality) берутся из параметров ключа, а не угадываются.
Ядро — sing-box v1.14.1 (libbox): XHTTP в нём нет (см. docs/IMPLEMENTATION.md, раздел 1c),
поэтому узел с этим транспортом даёт честную ошибку «транспорт не поддерживается ядром».

Границы безопасности:

- в UI и в модели состояния попадают только страна, город, протокол и транспорт;
- полный конфиг узла (UUID, пароль, ключ Reality) уходит в data/secret/SecretStore.kt —
  AES-256-GCM ключом из Android Keystore, без логирования и без облачного бэкапа;
- ядро туннеля получает конфиг точечно через SubscriptionImporter.configFor(nodeId).

---

## Сборка

Каноничная среда сборки — **GitHub Actions** (машина владельца слабая, локальные тяжёлые сборки не запускаем).

- Workflow: `.github/workflows/android.yml` → job `core` (переиспользуемый `libbox.yml`) собирает
  или достаёт из кэша `libbox.aar`, затем `./gradlew :app:assembleDebug`;
- Артефакт: **Actions → Android build → stravo-vpn-debug** (`app/build/outputs/apk/debug/app-debug.apk`).
- `app/libs/*.aar` в git не хранится: AAR подтягивается из кэша Actions, при промахе — пересобирается.

Локально (если действительно нужно):

```bash
./gradlew :app:assembleDebug     # нужен JDK 17+ и Android SDK, compileSdk 36
```

Автотесты в репозитории не добавляются и не запускаются — по правилу владельца
(см. [AGENTS.md](AGENTS.md)). Проверка UI — ручная, чек-лист в [docs/QA_CHECKLIST.md](docs/QA_CHECKLIST.md).

---

## Структура

```
app/src/main/java/com/stravo/vpn/
  core/            StravoConfig — единственное место с username бота и deep-link параметрами
  domain/model/    состояние подключения, локации, режимы, статистика, подписка, профиль
  domain/policy/   CapabilityPolicy — fail-closed: что доступно на телефоне, а что на TV
  domain/engine/   VpnEngine + контракт конфига и честная заглушка на случай чужого ABI
  engine/box/      ядро sing-box: StravoVpnService (PlatformInterface), SingBoxVpnEngine,
                   SingBoxConfigBuilder (ссылка узла → JSON), TunnelCore
  data/            настройки, профили, подписка, AppContainer (ручная DI)
  telegram/        BotLinks (чистый построитель ссылок) + BotLinkLauncher (tg:// → https → копия)
  pairing/         PairingSession/PairingState, PairingRepository, PairingQrEncoder, PairingBackend
  ui/theme/        палитра, типографика, токены, тема для двух форм-факторов
  ui/components/   PaperCanvas, PowerMedallion, StravoCard, PencilButton, QrCodeView, SMark...
  ui/mobile/       MobileRoot + экраны телефона
  ui/tv/           TvRoot + экраны TV (рельс, QR, успех)
  ui/state/        HomeUiState, HomeEvent, StravoViewModel
design-kit/        исходный комплект: токены, blueprint'ы, SVG, текстуры, мастер-задание
docs/              аудит, контракты, чек-лист приёмки
```

---

## Правила дизайна

- 85–90 % площади — бумага/графит, изумрудный — только активное состояние, фокус и «Быстрое подключение».
- Никаких щитов, замков, пейзажей и «киберпанка».
- Никаких абсолютных координат и картинок-скриншотов: только адаптивная Compose-раскладка
  (`BoxWithConstraints`, `LazyColumn`, `widthIn`, `aspectRatio`, `WindowInsets`).
- Декоративные Canvas — строго `matchParentSize() + clipToBounds()`.
- TV: фокус = лёгкое увеличение + двойная карандашная рамка + тонкий изумрудный кант.

---

## Безопасность

- В репозиторий и в логи не попадают URL подписок, UUID, ключи, токены и полные конфиги.
- Модель `Subscription` / `VpnProfile` хранит только безопасные метаданные.
- QR переноса содержит одноразовый токен на 5 минут, без секретов подписки.
