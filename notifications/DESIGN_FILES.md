# Files for design / icons / colors / theming

Этот документ — гайд для соавтора, который занимается **визуальной
частью** приложения. Сюда вынесены **только те файлы, которые
определяют внешний вид**: цвета, темы, формы (drawable), иконки,
лаунчер-иконки, layout-разметка экранов, звук тревоги, тексты.

Java-код (`app/src/main/java/...`) и Android-манифест в этой
работе **не нужны** — их трогает maintainer.

> Путь от корня репо одинаковый у всех файлов:
> `notifications/app/src/main/res/...`
> Здесь и далее показан **только хвост** — `drawable/...`,
> `layout/...` и т.д.

---

## 0. Общая структура `res/`

```
res/
??? drawable/             # shapes, фоны, vector-иконки
??? drawable-v24/         # forelayer для launcher icon (Android 7+)
??? layout/               # XML-разметка экранов и bottom-sheet'ов
??? mipmap-anydpi-v26/    # adaptive launcher icon
??? mipmap-hdpi..xxxhdpi/ # PNG launcher icon на разных плотностях
??? raw/                  # звук тревоги (alarm.mp3)
??? values/               # colors, strings, themes (light)
??? values-night/         # темы для тёмного режима (сейчас совпадают)
??? xml/                  # FileProvider config (трогать не надо)
```

---

## 1. Палитра, текст, темы

Главные «глобальные» точки настройки внешнего вида:

| Файл | Что трогать |
|------|-------------|
| `values/colors.xml` | **Все** цвета приложения. Поделены на 3 секции: «старые» (можно игнорировать), «dark theme» (`dark_*`) и «neon dark» (`bg_*`, `accent_*`, `text_*`, `status_*`) — это то, чем пользуются современные экраны. |
| `values/themes.xml` | Theme.Notifications (основная тема) + Theme.Notifications.Dialog (тема для full-screen алёрта). Здесь живут `colorPrimary`, статусбар и т.п. |
| `values-night/themes.xml` | Тёмная версия темы. Сейчас она по сути дублирует обычную, потому что у нас «всегда тёмное» приложение. Если решим вводить light-mode — это будет здесь. |
| `values/strings.xml` | Все тексты UI. Поделены на секции (Login, Register, Main, Settings, Profile, Reports, Alert dialog). Можно править только тексты — не трогая `name=...`. |
| `values/ids.xml` | Прединициализированные ID'шники (используются из Java-кода). **Не трогать.** |

### Текущая палитра (выжимка из `values/colors.xml`)

| Ключ | HEX | Где используется |
|------|-----|------------------|
| `@color/bg_panel` | `#171717` | Основной фон главного экрана + bottom-sheet'ы |
| `@color/bg_tile` | `#222222` | Pill'ы, карточки настроек, footer |
| `@color/bg_panel_high` | `#1E1E1E` | Чуть более светлый — placeholder аватара, edit-chip |
| `@color/bg_slot_idle` | `#262626` | Пустой статус-слот на главном экране |
| `@color/accent_white` | `#FFFFFF` | Тинт всех иконок, белые рамки активных слотов |
| `@color/accent_red` | `#FFFF4D4F` | «Not ready» обводка слота |
| `@color/text_primary` | `#FFFFFF` | Заголовки, основной текст |
| `@color/text_secondary` | `#FF8E8E8E` | Подзаголовки, второстепенный текст |
| `@color/status_ready_fg` | `#FF66BB6A` | Зелёный «Ready» в отчётах и списке |
| `@color/status_not_ready_fg` | `#FFEF5350` | Красный «Not ready» |
| `@color/status_pending_fg` | `#FFBDBDBD` | Серый «No response» |

---

## 2. Иконки внутри приложения (vector drawable)

Это **все** `ic_*.xml` в `drawable/`. Каждая иконка — стандартный
Android `<vector>` 24?24 dp с `android:tint="@color/accent_white"`.
Чтобы поменять иконку:

1. Подготовь SVG (24?24, monochrome — цвет даст тинт).
2. В Android Studio: правый клик на `drawable/` ? **New ? Vector
   Asset ? Local file** ? выбрать SVG ? имя оставить старое.
3. Открой `.xml`, убедись, что у `<vector>` стоит
   `android:tint="@color/accent_white"`.

| Файл | Где используется |
|------|------------------|
| `drawable/ic_heart.xml` | Открывалка меню снизу на главном экране + handle сверху меню + «back» в footer Settings (повёрнутая) |
| `drawable/ic_menu_handle.xml` | Альтернативный handle (сейчас в `activity_main.xml`) |
| `drawable/ic_send.xml` | Тайл «Send» в меню |
| `drawable/ic_users.xml` | Тайл «Users» в меню |
| `drawable/ic_history.xml` | Тайл «History» в меню |
| `drawable/ic_bell.xml` | Тайл «Bell» в меню + «Notifications» в Settings |
| `drawable/ic_gear.xml` | Тайл «Settings» в меню |
| `drawable/ic_chevron_right.xml` | Стрелка вправо внутри тайла «Send» |
| `drawable/ic_chevron_up.xml` | Старая стрелка-открывалка меню (сейчас заменена сердцем, но файл оставлен) |
| `drawable/ic_battery.xml` | Settings ? Battery |
| `drawable/ic_layers.xml` | Settings ? Display over other apps |
| `drawable/ic_lock_screen.xml` | Settings ? Lock screen |
| `drawable/ic_refresh.xml` | Settings ? Updates |
| `drawable/ic_edit.xml` | Маленький карандаш в углу профиль-карточки |
| `drawable/ic_profile.xml` | Тайл «Profile» в Settings (если используется) |
| `drawable/ic_person_placeholder.xml` | Заглушка вместо аватара, когда его нет |
| `drawable/ic_notification.xml` | **Status-bar иконка** push-уведомления (важно: должна быть монохромная, белая на прозрачном) |

---

## 3. Иконка приложения (launcher icon)

| Файл | Что |
|------|-----|
| `drawable/ic_launcher_background.xml` | Подложка adaptive-иконки |
| `drawable-v24/ic_launcher_foreground.xml` | Передний слой adaptive-иконки |
| `mipmap-anydpi-v26/ic_launcher.xml` | Adaptive-обёртка, склеивающая два слоя выше (Android 8+) |
| `mipmap-anydpi-v26/ic_launcher_round.xml` | То же для «круглых» лаунчеров |
| `mipmap-hdpi/ic_launcher.png` <br>+ `_round.png` | Растровая версия для устройств <Android 8 (Pre-Oreo) |
| `mipmap-mdpi/...` <br>`mipmap-xhdpi/...` <br>`mipmap-xxhdpi/...` <br>`mipmap-xxxhdpi/...` | То же на остальных плотностях |

**Как заменить иконку приложения:**

В Android Studio: **File ? New ? Image Asset ? Launcher Icons (Adaptive
and Legacy)**. Студия сама перегенерирует все 12 файлов выше из
исходного SVG/PNG, нужно только указать имя `ic_launcher`.

---

## 4. Формы / фоны (shape drawable)

Это `bg_*.xml` в `drawable/`. У каждого — обычно `<shape>` с
`solid`, `corners`, иногда `stroke`. Открываются и редактируются
обычным текстовым редактором.

### 4.1 Главный экран

| Файл | Что отрисовывает |
|------|------------------|
| `bg_status_panel.xml` | Большая тёмная плашка вокруг 5 слотов |
| `bg_slot_idle.xml` | Пустой слот (тёмный квадрат с радиусом) |
| `bg_slot_ready.xml` <br>`bg_slot_not_ready.xml` | Обводки активных слотов (legacy — больше не используются напрямую) |
| `bg_slot_ring_white.xml` <br>`bg_slot_ring_red.xml` | Тонкие кольца поверх слотов: белое = ready, красное = not ready |
| `bg_slot_self_dot.xml` | Маленькая белая точка в верхнем правом углу «моего» слота |
| `bg_timer_progress.xml` | Полоска прогресса под слотами (60-секундный broadcast lock) |

### 4.2 Меню и Settings (bottom-sheet)

| Файл | Что отрисовывает |
|------|------------------|
| `bg_pill_outlined.xml` | Pill с белой тонкой обводкой (Send tile) |
| `bg_pill_filled.xml` | Pill без обводки (Users, History) |
| `bg_squircle.xml` | Маленький квадрат со скруглением для Bell/Gear |
| `bg_settings_card.xml` | Карточка permissions без обводки |
| `bg_settings_card_outlined.xml` | «Hero» profile-карточка с белой обводкой |
| `bg_settings_footer.xml` | Тёмная плашка футера со стрелкой назад |
| `bg_edit_chip.xml` | Круглая кнопка-карандаш в углу профиль-карточки |

### 4.3 Профиль и аватары

| Файл | Что отрисовывает |
|------|------------------|
| `bg_avatar_circle.xml` | Круг для аватара в `ProfileActivity` |
| `bg_avatar_small.xml` | Круг для маленьких аватаров в списке пользователей |
| `bg_avatar_square.xml` | Скруглённый квадрат для аватара в Settings profile card |
| `bg_online_dot.xml` | Зелёная точка «онлайн» поверх аватара в списке (Discord-style) |

### 4.4 Диалоги и поля ввода

| Файл | Что отрисовывает |
|------|------------------|
| `bg_dialog.xml` | Фон custom-диалога |
| `bg_edit_text.xml` | Скруглённый фон полей ввода логина/пароля и т.п. |
| `bg_status_ready.xml` <br>`bg_status_not_ready.xml` <br>`bg_status_pending.xml` | Полупрозрачные подложки строк в `AlertDetailActivity` (отчёт «кто как ответил») |

---

## 5. Layout-разметка экранов

Это `layout/*.xml`. Здесь живёт **что куда положено** на каждом
экране/листе. Цвета и формы они берут через `@color/...` и
`@drawable/bg_...`, поэтому если правишь палитру в `colors.xml` —
layout трогать не надо.

| Файл | Какой экран |
|------|-------------|
| `layout/activity_main.xml` | **Главный экран** (заголовок, 5 слотов, таймер, кнопка-сердце снизу) |
| `layout/bottom_sheet_menu.xml` | **Главное меню** (Send/Users/History/Bell/Gear) — открывается с главного экрана |
| `layout/bottom_sheet_settings.xml` | **Settings sheet** — profile card + два masonry-грида + footer back |
| `layout/activity_profile.xml` | Полноэкранный профиль (аватар, displayName, смена пароля, удаление аккаунта) |
| `layout/activity_users.xml` | Список пользователей (online/offline) |
| `layout/activity_reports.xml` | Список отправленных alert'ов |
| `layout/activity_alert_detail.xml` | Детали одного alert'а — кто ready / not ready / no response |
| `layout/activity_login.xml` | Экран входа |
| `layout/activity_register.xml` | Экран регистрации |
| `layout/activity_dialog.xml` | Full-screen «Ready / Not ready» при срабатывании тревоги |
| `layout/overlay_dialog.xml` | Поверх-другие-приложения версия того же алёрта (с `SYSTEM_ALERT_WINDOW`) |

**Важно:** не переименовывай и не удаляй `android:id="@+id/..."` —
по ним Java-код находит элементы. Тексты, фоны, отступы, размеры,
порядок children — менять можно.

---

## 6. Звук

| Файл | Что |
|------|-----|
| `raw/alarm.mp3` | Дефолтный звук тревоги. Пользователь может выбрать свой через тайл «Bell», но если он не выбран — играет этот. Можно заменить на любой `.mp3` / `.wav` / `.ogg` (имя файла оставить тем же). |

---

## 7. Что **не** трогать соавтору

- `app/src/main/AndroidManifest.xml` — там разрешения, activity и FCM-сервис.
- Любые `.java`-файлы в `app/src/main/java/com/goddddd/notification/` — это бизнес-логика и привязки UI к данным.
- `build.gradle` (оба), `gradle.properties`, `settings.gradle` — конфигурация сборки.
- `app/google-services.json` — Firebase-конфиг.
- `app/proguard-rules.pro` — правила обфускации релиза.
- `release.keystore`, `keystore.properties` — приватные ключи подписи.
- `xml/file_paths.xml` — конфиг FileProvider для self-update.
- `values/ids.xml` — ссылки на элементы из Java.

---

## 8. Workflow для соавтора

```bash
# 1. Поставить окружение (см. ENVIRONMENT.md)
# 2. Клонировать
git clone https://github.com/goddammit1/app.git
cd app/notifications

# 3. Создать ветку под задачу (например, "новая палитра")
git checkout -b design/new-palette

# 4. Работать только в:
#    app/src/main/res/
#    (drawable/, layout/, values/, mipmap-*/, raw/)

# 5. Проверить локально debug-сборкой
./gradlew assembleDebug      # Linux/macOS
.\gradlew.bat assembleDebug  # Windows
# Установить app/build/outputs/apk/debug/app-debug.apk на телефон

# 6. Закоммитить, запушить ветку, открыть Pull Request
git add app/src/main/res/
git commit -m "design: краткое описание"
git push origin design/new-palette
# Дальше PR на GitHub - его смерджит maintainer.
```

---

## 9. TL;DR — куда смотреть в первую очередь

| Хочу поменять... | Файл |
|------------------|------|
| Все цвета | `values/colors.xml` |
| Все тексты | `values/strings.xml` |
| Скругления карточек/pill'ов | `drawable/bg_*.xml` |
| Иконки в меню/настройках | `drawable/ic_*.xml` |
| Иконку приложения | Android Studio ? New ? Image Asset |
| Расположение элементов на экране | `layout/<имя>.xml` |
| Звук будильника | `raw/alarm.mp3` |
| Theme / colorPrimary / статусбар | `values/themes.xml` (+ `values-night/themes.xml`) |