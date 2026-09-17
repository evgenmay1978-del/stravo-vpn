# STRAVO VPN — QR-перенос подписки Phone → Android TV

## Цель
Пользователь должен добавить свою подписку в Android TV без ручного ввода URL, логина, UUID, конфигурации или длинных кодов с пульта.

Основной сценарий:
1. Пользователь открывает STRAVO VPN на Android TV.
2. Выбирает «Добавить подписку с телефона».
3. TV создаёт короткую одноразовую pairing session.
4. На TV показывается QR.
5. Пользователь сканирует QR камерой телефона.
6. Открывается Telegram bot `@MaestroSecureVPN_bot`.
7. Бот показывает понятное подтверждение:
   «Добавить вашу подписку на телевизор STRAVO?»
8. Пользователь нажимает «Добавить».
9. Backend привязывает subscription/account к pairing session.
10. TV автоматически получает результат, сохраняет subscription и переходит на главный экран.
11. Пользователь ничего вручную не копирует.

---

## КРИТИЧЕСКАЯ БЕЗОПАСНОСТЬ

НЕЛЬЗЯ помещать в QR:
- реальный subscription URL;
- access token подписки;
- UUID/VLESS key;
- пароль;
- полный конфиг;
- API secret.

QR содержит ТОЛЬКО одноразовый pairing token / pairing id.

Требования к pairing token:
- криптографически случайный;
- одноразовый;
- короткий срок жизни, рекомендуемо 2–5 минут;
- после успешного claim немедленно становится недействительным;
- rate limited;
- не должен сам по себе давать доступ к подписке.

TV создаёт собственный `device_install_id`.
Если используется передача чувствительного config payload — рекомендуется:
- сгенерировать TV keypair;
- public key отправить при `pair/create`;
- payload шифровать для TV public key;
- private key хранить через Android Keystore.

---

## Backend API

Названия endpoint можно адаптировать под существующий backend.

### POST /v1/pair/create
Вызывает TV.

Request:
```json
{
  "deviceInstallId": "...",
  "platform": "android_tv",
  "appVersion": "...",
  "devicePublicKey": "..."
}
```

Response:
```json
{
  "pairingId": "opaque-id",
  "pairingToken": "short-single-use-token",
  "expiresAt": "ISO-8601",
  "botDeepLink": "https://t.me/MaestroSecureVPN_bot?start=pair_<TOKEN>"
}
```

### POST /v1/pair/claim
Вызывает backend Telegram-бота после подтверждения пользователя.

Request:
```json
{
  "pairingToken": "...",
  "telegramUserId": "...",
  "subscriptionId": "..."
}
```

Response:
```json
{
  "status": "claimed"
}
```

Backend проверяет:
- pairing token существует;
- не истёк;
- ещё не использован;
- Telegram user имеет право на subscription;
- subscription активна.

### GET /v1/pair/status/{pairingId}
TV fallback polling.

Response до подтверждения:
```json
{
  "status": "waiting"
}
```

Response после подтверждения:
```json
{
  "status": "completed",
  "subscriptionPayload": "encrypted-or-tokenized-payload"
}
```

Вместо polling предпочтителен WebSocket/SSE, если backend уже это поддерживает.
Если нет — polling каждые 2–3 секунды до expiresAt вполне приемлем.

---

## Telegram Bot Flow

Deep link:
`https://t.me/MaestroSecureVPN_bot?start=pair_<TOKEN>`

Bot:
1. принимает start payload;
2. валидирует token через backend;
3. если у пользователя несколько подписок — показывает список;
4. если одна — сразу показывает подтверждение;
5. кнопка:
   `Добавить на телевизор`;
6. после успеха:
   `Готово. Подписка добавлена в STRAVO VPN на телевизоре.`
7. expired token:
   `QR-код устарел. Обновите код на телевизоре.`

Никаких конфигов и секретов в сообщениях Telegram.

---

## TV UI

Экран/диалог:

Заголовок:
`Добавить подписку`

Подзаголовок:
`Отсканируйте QR-код телефоном`

QR крупный, контрастный, минимум декоративной графики вокруг него.

Под QR:
`Код действует 03:00`

Состояния:
- CREATING
- WAITING_FOR_PHONE
- CLAIMED
- IMPORTING
- SUCCESS
- EXPIRED
- ERROR

После SUCCESS:
- лёгкая анимация graphite → emerald;
- текст:
  `Подписка добавлена`
- автоматически перейти на Home через ~1 секунду;
- Home уже показывает Profile/Location из новой подписки.

TV buttons:
- `Обновить QR`
- `Назад`

D-pad focus обязателен.

---

## Mobile STRAVO App

Если пользователь сканирует QR не камерой, а из STRAVO Mobile:
- добавить действие `Сканировать QR с телевизора`;
- использовать CameraX/ML Kit/ZXing scanner;
- после распознавания `stravo://pair?...` или bot URL:
  - открыть confirmation;
  - отправить claim через account/backend;
  - показать success.

Если подписка управляется исключительно Telegram-ботом, STRAVO Mobile может просто открыть bot deep link.

---

## Storage on TV

После получения:
- subscription metadata хранить локально;
- секреты/refresh token — через Android Keystore / Encrypted storage;
- не писать subscription URL или token в обычный logcat;
- не выводить секреты в analytics/crash reports.

---

## Offline / Failure cases

1. QR истёк:
   - показать `QR-код устарел`;
   - `Обновить QR`.

2. TV потерял интернет:
   - экран остаётся;
   - автоматический retry;
   - понятный статус.

3. Телефон подтвердил, а TV временно offline:
   - backend держит completed session ограниченное время;
   - TV получает её после reconnect.

4. Подписка неактивна:
   - bot сообщает причину;
   - TV остаётся в waiting/error без фейкового success.

5. Claim уже использован:
   - отказ;
   - создать новый QR.

---

## Acceptance Criteria

[ ] На TV можно открыть pairing screen только D-pad.
[ ] QR генерируется динамически.
[ ] QR не содержит subscription secret.
[ ] QR истекает.
[ ] Bot deep-link открывает правильную pairing session.
[ ] После подтверждения в Telegram подписка появляется на TV без ручного ввода.
[ ] Повторное использование token невозможно.
[ ] В logcat нет secrets.
[ ] После импорта Home показывает реальный профиль/локацию.
[ ] EXPIRED/ERROR состояния обработаны.
[ ] Есть unit tests для token state machine.
[ ] Есть integration test create -> claim -> status -> import.
