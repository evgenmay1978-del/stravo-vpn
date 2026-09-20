# Карандашное оформление — 20.09.2026

Референс: `Screenshot_20260920_113236.jpg`, предоставлен владельцем.
Сохранены меню, подписи действий, обработчики, настройки, VPN-логика и запрет CDN на TV.
Оформление распространено через общие Compose-компоненты: бумага, графитовая штриховка,
рисованные рамки, зелёный медальон и щит-компас, заголовки с засечками.

## Ассеты и промпты ImageGen

Использован встроенный ImageGen с референсом владельца. Все ассеты сохранены в
`app/src/main/res/drawable-nodpi/`. Ни один ассет не содержит готового экрана,
меню, статуса соединения или чисел трафика. Текст и управление остаются нативными.

- `atlas_pencil_background.png`: refined graphite mountains, conifer forest, winding
  stream and small compass on fibrous ivory paper; quiet central area; no lettering,
  phone frame, buttons, cards, metrics or menus. Portrait 3:4 background plate.
- `atlas_pencil_medallion.png`: front-on circular dark forest-green coloured-pencil
  medallion, dense graphite hatching, pale sage annulus, matte paper tooth; blank
  centre, no power symbol or lettering; transparent outside. Power glyph/state are native.
- `atlas_pencil_crest.png`: tall green pencil shield with eight-point compass rose,
  graphite double outline, ivory highlights inside; transparent outside, no text.

`pencilSurface` кэширует рисунок рамок и волокон по размеру. `PencilIcon` добавляет
второй лёгкий контур к существующим символам, сохраняя их accessibility-описания.
Структура экранов и события не менялись. Установка/просмотр на физическом устройстве
не выполнены в этой сессии; телефон по ADB недоступен.
