# STRAVO VPN — МАСТЕР-ЗАДАНИЕ ДЛЯ CODEX

## Роль
Ты senior Android engineer + UI engineer. Твоя задача — довести STRAVO VPN до рабочего, устойчивого приложения для:
1. Android phone/tablet;
2. Android TV / Google TV;
3. одного общего codebase и общей бизнес-логики.

Не делай «красивый скриншот поверх приложения». Интерфейс должен быть нативным, адаптивным, тестируемым и реально работать на разных размерах.

---

# 0. ПЕРВОЕ ДЕЙСТВИЕ: АУДИТ ПРОЕКТА
Если репозиторий уже существует:
- НЕ создавай новый проект поверх него;
- сначала изучи `settings.gradle(.kts)`, `build.gradle(.kts)`, `libs.versions.toml`, `AndroidManifest.xml`, структуру модулей;
- найди существующий `VpnService`, native core, tunnel engine, repository/API, хранение профилей и локаций;
- найди текущую навигацию и главные Compose/XML экраны;
- найди package/applicationId;
- собери проект как есть и зафиксируй исходные ошибки.

Перед серьёзными изменениями дай короткий отчёт:
- что уже работает;
- что сломано;
- что переиспользуем;
- какие файлы планируешь менять;
- есть ли настоящий VPN core.

КРИТИЧЕСКИ: если VPN core отсутствует, НЕ имитируй подключение фейковым таймером. UI и Telegram-flow можно закончить, но для реального туннеля отдельно укажи, какой backend/core/API отсутствует.

---

# 1. ВИЗУАЛЬНЫЙ ЯЗЫК

## Концепция
STRAVO = технический карандашный эскиз премиум-уровня:
- светлая кремовая бумага;
- графит / уголь / технические линии;
- фирменная крупная буква S;
- тонкие чертёжные окружности и направляющие;
- зелёный/изумрудный применяется только как небольшой функциональный акцент.

ЗАПРЕЩЕНО:
- щиты;
- замки;
- горы/леса/пейзажи на фоне;
- типовые «киберпанк VPN» клише;
- избыточный glow;
- градиентная каша;
- Material-компоненты «как из коробки» без стилизации;
- UI, собранный абсолютными px-координатами;
- цельные картинки экранов вместо настоящей верстки.

## Цвета
Используй `design_tokens.json`.
Цветовая иерархия:
- 85–90%: cream / graphite / white-black pencil;
- 10–15% максимум: emerald active accents.

## Текстуры
`paper_texture.png` — очень тихий фон.
`graphite_noise.png` — только маски/накладки с низкой opacity.
Никакая текстура не должна снижать читаемость текста.

---

# 2. АРХИТЕКТУРА UI

Используй Jetpack Compose.

## Mobile
- Material3 только как foundation, внешний вид полностью STRAVO.
- `Scaffold`, `WindowInsets.safeDrawing`, `navigationBarsPadding()`.
- `LazyColumn` для содержимого, которое может не помещаться.
- `Modifier.fillMaxWidth()`, `widthIn(...)`, `aspectRatio(...)`.
- Никаких фиксированных координат и высот, которые ломаются на Samsung/Xiaomi/малых экранах.
- Корректная работа с display cutout, статус-баром, gesture navigation.
- Проверка fontScale минимум 1.0 и 1.3.

## TV
Определи TV через `Configuration.UI_MODE_TYPE_TELEVISION`.
Для TV создай отдельный composition root / layout, но используй те же:
- ViewModel;
- repositories;
- domain state;
- navigation destinations;
- design tokens.

Используй Compose for TV / TV-optimized components там, где нужен D-pad focus.
Не смешивай Mobile MaterialTheme и TV MaterialTheme в одном composition tree.

TV:
- reference 1920×1080;
- safe margins;
- каждое действие достижимо D-pad;
- всегда виден текущий focus;
- focus = лёгкое увеличение + двойная карандашная рамка + тонкий emerald edge;
- никаких hover-only действий;
- никаких touch-жестов как единственного способа управления.

---

# 3. ГЛАВНЫЙ ЭКРАН — MOBILE

Порядок:
1. Header:
   - маленький `brand_s_mark`;
   - STRAVO VPN;
   - подзаголовок «СВОБОДА В СЕТИ»;
   - Settings;
   - без лишней россыпи иконок.

2. Main connection medallion:
   - большой круг;
   - карандашные concentric drafting rings (`drafting_overlay.svg`);
   - внутри нативно нарисованный power symbol;
   - состояния:
     - DISCONNECTED;
     - CONNECTING;
     - CONNECTED;
     - ERROR.
   - emerald только у активного состояния.

3. Status:
   - DISCONNECTED: «ГОТОВО К ПОДКЛЮЧЕНИЮ»
   - CONNECTING: «ПОДКЛЮЧЕНИЕ…»
   - CONNECTED: «ЗАЩИЩЕНО»
   - ERROR: понятная русская причина / retry.

4. Card «Локация»
5. Card «Профиль»
6. ОДНА карточка режима:
   - «Обычный VPN»
   - «Свободный интернет»

Полностью удалить из проекта пользовательский пункт:
«Обход белых списков».
Удалить не только визуально, но и:
- строки;
- state;
- navigation;
- analytics event;
- unused icons;
- dead code, если он относился только к этому экрану.

7. Stats:
   - Пинг;
   - Загрузка;
   - Отдача.
   До измерения показывать длинное тире, не фейковые числа.

8. Главная CTA:
   «Быстрое подключение»

9. Bottom navigation:
   - Главная;
   - Локации;
   - Профиль;
   - Настройки.

---

# 4. ГЛАВНЫЙ ЭКРАН — TV

Не растягивай мобильный экран.

Layout:
- слева ~40%: фирменный знак / connection medallion / status;
- справа ~60%:
  1. Локация
  2. Профиль
  3. Обычный VPN
  4. Быстрое подключение
- снизу/вторичным рядом: stats и Settings при необходимости.

При старте:
- focus должен быть на самом логичном действии;
- если аккаунт/профиль уже настроен — на «Быстрое подключение»;
- если нет — на «Профиль» или «Локация», в зависимости от состояния.

---

# 5. «БЫСТРОЕ ПОДКЛЮЧЕНИЕ» → TELEGRAM BOT

Конфигурацию держать в ОДНОМ месте.

По текущей конфигурации:
BOT_USERNAME = "MaestroSecureVPN_bot"
START_PARAM_MOBILE = "stravo_quick_connect"
START_PARAM_TV = "stravo_tv_quick_connect"

Не размазывать username по проекту.

## Mobile behavior
По нажатию:
1. попробовать:
   `tg://resolve?domain=MaestroSecureVPN_bot&start=stravo_quick_connect`
2. если Telegram handler отсутствует / ActivityNotFoundException:
   открыть:
   `https://t.me/MaestroSecureVPN_bot?start=stravo_quick_connect`
3. если и это невозможно:
   показать Snackbar/Dialog с ссылкой и Copy.

Не запрашивать лишних permissions.
Не использовать WebView только ради Telegram.

## Android TV behavior
На TV Telegram часто отсутствует, поэтому:
1. при нажатии открыть STRAVO dialog/sheet:
   - QR code для
     `https://t.me/MaestroSecureVPN_bot?start=stravo_tv_quick_connect`
   - текст «Отсканируйте камерой телефона»
   - кнопка «Открыть ссылку» для браузера/handler;
   - кнопка Back закрывает dialog.
2. QR генерировать локально.
3. сам URL остаётся HTTPS fallback.

Deep-link функцию вынести в отдельный testable class/service:
`BotLinkLauncher`.

---

# 6. VPN CORE

Если в существующем проекте уже есть tunnel engine:
- сохранить его;
- ViewModel получает real state из engine/repository;
- НЕ связывать Compose напрямую с service;
- кнопка центрального power может управлять настоящим VPN, если это уже логика проекта.

Если настоящего core нет:
- НЕ притворяться, что VPN подключён;
- оставить architecture interface:
  `VpnEngine`
  - observeState()
  - connect(profile, location)
  - disconnect()
- UI должен компилироваться и работать, а отсутствующий core документировать отдельно.

«Быстрое подключение» в любом случае открывает бота, как описано выше.

---

# 7. СОСТОЯНИЕ И DATA FLOW

Рекомендуемая схема:
UI -> ViewModel -> UseCases/Repository -> VpnEngine/API/Storage

Один immutable `HomeUiState`, например:
- connectionState
- selectedLocation
- selectedProfile
- stats
- isTv
- botLinkState / error
- loading/error fields

Все side-effects через события:
- OnPowerClick
- OnLocationClick
- OnProfileClick
- OnQuickConnectClick
- OnSettingsClick
- OnRetry

Не хранить Activity/Context во ViewModel.

---

# 8. ASSETS

В комплекте:
- `brand_s_mark.svg`
- adaptive icon layers;
- themed monochrome icon;
- TV banner 320×180;
- pencil icons;
- `drafting_overlay.svg`;
- `tv_focus_frame.svg`;
- paper/graphite textures;
- implementation blueprints.

Конвертируй source SVG в Android VectorDrawable там, где это разумно.
Сложную paper texture держать raster WebP/PNG.
Не конвертируй все подряд в bitmap.

---

# 9. ADAPTIVE ICON / TV

Android Adaptive Icon:
- foreground и background раздельно;
- основной знак остаётся внутри безопасной области;
- monochrome layer обязателен для themed icon;
- не запекай rounded-square mask в foreground.

Android TV:
- добавь TV banner;
- manifest должен поддерживать TV без требования touchscreen;
- LEANBACK_LAUNCHER для TV;
- один общий applicationId/codebase, если текущая архитектура это позволяет.

---

# 10. RESPONSIVE RULES — КРИТИЧЕСКИ

Это исправляет текущую проблему, когда рисунки уезжают за экран.

НЕЛЬЗЯ:
- `offset(x = XXX.dp, y = XXX.dp)` как способ верстки;
- фиксировать высоту всего экрана;
- позиционировать главный UI Canvas-координатами;
- использовать 2160×4670 картинку как screen background с содержимым внутри.

МОЖНО:
- BoxWithConstraints;
- WindowInsets;
- LazyColumn;
- weights только там, где они дают предсказуемый результат;
- widthIn/heightIn;
- aspectRatio;
- arrangement/spacing;
- responsive breakpoints;
- Canvas только для декоративных линий внутри bounds самого компонента.

Декоративный Canvas ВСЕГДА:
`Modifier.matchParentSize().clipToBounds()`
и рисует только внутри локальной области.

---

# 11. НАВИГАЦИЯ

Нужны destinations:
- Home
- Locations
- Profile
- Settings

Mobile: bottom navigation.
TV: D-pad friendly rail/row либо контекстная навигация без постоянной панели, если так чище.

Back:
- dialog -> закрывается;
- nested destination -> Home;
- Home -> системное поведение.

---

# 12. ACCESSIBILITY

Mobile:
- touch targets >= 48dp;
- contentDescription всем actionable icons;
- достаточный contrast;
- stateDescription у connect control.

TV:
- читаемый focus;
- focus не теряется после recomposition;
- D-pad проходит все действия;
- TalkBack/Accessibility не ломается из-за Canvas.

---

# 13. QA

После каждой крупной фазы:
1. `./gradlew assembleDebug`
2. unit tests
3. lint
4. install на emulator/device
5. screenshots
6. logcat на ошибки

Проверить минимум:
MOBILE:
- узкий телефон;
- типичный 1080p/1440p Samsung;
- gesture navigation;
- 3-button navigation;
- fontScale 1.0 и 1.3;
- light UI;
- screen rotation не должна ломать state.

TV:
- Android TV emulator 1080p;
- полный проход только D-pad;
- focus везде виден;
- Back;
- quick connect QR;
- browser fallback;
- никакой контент не выходит за safe area.

Для adb QA:
- `adb devices`
- install debug variant;
- resolve/start activity;
- UI tree;
- D-pad keyevents;
- screenshot;
- logcat.

---

# 14. ACCEPTANCE CRITERIA

Работа считается завершённой, только если:

[ ] Приложение собирается без ошибок.
[ ] Нет runtime crash на phone и TV.
[ ] Ни один элемент не выходит за экран на тестовых размерах.
[ ] «Обход белых списков» удалён полностью.
[ ] «Обычный VPN» остаётся.
[ ] «Быстрое подключение» открывает Telegram bot на phone.
[ ] На TV «Быстрое подключение» показывает QR + open-link fallback.
[ ] D-pad navigation проходит все TV actions.
[ ] Adaptive icon не обрезается системными mask.
[ ] TV banner присутствует.
[ ] Карандашный стиль выдержан без щитов/пейзажей.
[ ] Emerald используется только как функциональный акцент.
[ ] UI state переживает configuration changes.
[ ] Есть unit test для bot URL builder и fallback logic.
[ ] Есть скриншоты mobile + TV после финальной сборки.
[ ] README содержит build/run/test инструкции.

---

# 15. ПОРЯДОК РАБОТЫ CODEX

Не делай всё одним огромным изменением.

Фаза A — Audit + baseline build.
Фаза B — Design system + assets.
Фаза C — responsive mobile Home.
Фаза D — navigation/screens.
Фаза E — Telegram Quick Connect.
Фаза F — Android TV root + focus + QR.
Фаза G — adaptive icon + TV banner + manifest.
Фаза H — tests/QA/fixes.
Фаза I — release candidate.

После каждой фазы:
- перечисли изменённые файлы;
- покажи результат build/test;
- перечисли оставшиеся риски;
- только потом переходи дальше.

Если видишь противоречие в существующем проекте — сначала объясни, затем исправляй.
Не удаляй рабочий VPN core ради дизайна.
Не выдумывай backend/API.


---

# 16. ОБЯЗАТЕЛЬНО: QR-ПЕРЕНОС ПОДПИСКИ PHONE → TV

Это обязательная пользовательская функция.

На Android TV добавить действие:
`Добавить подписку с телефона`

Flow:
TV -> create pairing session -> show QR -> phone scans -> Telegram bot confirms ->
backend binds subscription -> TV automatically receives/imports -> Home updates.

Полная спецификация:
`docs/STRAVO_QR_SUBSCRIPTION_PAIRING.md`

КРИТИЧЕСКИ:
- QR НЕ содержит subscription URL/config/secret.
- QR содержит только short-lived single-use pairing token.
- token истекает через 2–5 минут.
- после claim token нельзя использовать повторно.
- subscription secrets не логируются.
- TV показывает WAITING / CLAIMED / IMPORTING / SUCCESS / EXPIRED / ERROR.
- после SUCCESS подписка реально появляется в Profile/Locations.
- если backend ещё не имеет pairing API, сначала реализуй backend contract/stub interface,
  но не имитируй успешный перенос фейковым local delay.

Telegram deep-link:
`https://t.me/MaestroSecureVPN_bot?start=pair_<TOKEN>`

Добавить отдельные testable компоненты:
- `PairingRepository`
- `PairingSession`
- `PairingState`
- `CreatePairingSessionUseCase`
- `ObservePairingStatusUseCase`
- `ImportSubscriptionUseCase`
- `PairingQrEncoder`

На TV:
- QR крупный;
- D-pad focus;
- `Обновить QR`;
- `Назад`;
- countdown;
- auto refresh only after explicit expiry/retry policy.

На mobile:
- отдельный экран `Перенести подписку на TV`;
- scanner or bot deep-link;
- success confirmation.

Acceptance criteria расширить:
[ ] QR phone→TV перенос подписки работает end-to-end.
[ ] Подписка появляется на TV без ручного ввода.
[ ] QR безопасный, одноразовый, с TTL.
[ ] Повторный claim отклоняется.
[ ] Expired flow проверен.
[ ] QR flow проверен на Android TV emulator + реальном телефоне/эмуляторе.
