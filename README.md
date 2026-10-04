# Boltun

**English** · [Русский](#русский)

Android app: reads the selected and copied text aloud by tapping a floating button over other apps.

Author: **Tar3ah**

## Features

### Text reading
- **Floating button** over any app: draggable, the position is remembered.
- **Select → tap → it speaks.** Captures the selection without manual "Copy". Not yet everywhere; to be safe, copy first.
- Text is split into sentences and read in order; the UI shows the position `7 / 42`.
- If a selection isn't available (some WebView/OEM apps), the clipboard is used.

### Control menu (double-tap the button)
- Buttons ⏮ back · ⏯ start/pause · ⏹ stop · ⏭ forward · speed. Seek by sentence.
- Second row: ↻ replay from start · 🕓 continue where left off · ⚙ app/settings · ✕ close.
- Sentence counter, updates as reading progresses.
- The menu appears automatically during playback and flips to whichever side has room.
- Long press the button — stop.

### Ways to start
- Select text + floating button — the main scenario. Doesn't work in every app, see "Known limitations".
- More reliable: select → "Copy" → tap the floating button (the clipboard is used).
- "Share" — send text to Boltun from another app.
- In the notification shade, tap the play button.

### Speech synthesis
- System default TTS engine or any installed one (engine list in the app).
- Speeds: 0.75 · 0.90 · 1.00 · 1.10 · 1.25 · 1.50 · 2.00.
- Language and voice are inherited from the selected engine.
- Recommended engine for Russian: [ruvoice-tts](https://github.com/kost-t-human/ruvoice-tts).

## First run

1. "Allow display over other apps" — opens system settings.
2. "Enable Accessibility service" — enable **Boltun**.
3. "Allow notifications" (Android 13+).
4. "Disable battery optimization" — so the service isn't killed.
5. "Show floating button".

## Usage

- Select text in any app and tap the floating button (single tap).
- **Double tap** the button — open/close the control menu.
- Menu (appears automatically during playback):
  - sentence counter `7 / 42` (updates as reading progresses);
  - top row: ⏮ back, ⏯ start/pause, ⏹ stop, ⏭ forward, speed;
  - bottom row: ↻ replay from start, 🕓 continue where left off, ⚙ app, ✕ close.
- Long press the button — stop.
- The button is draggable; the position is remembered and the menu flips to the side with room.
- Via the selection menu you can choose "Boltun" (`PROCESS_TEXT`) or "Share".
- The "Check selection capture" button shows what was captured and from which source
  (`cache` — selection event, `node` — node, `copy` — via copy, `clipboard` — clipboard).
- The current **engine** is shown, with two buttons:
  - "Default speech engine" — system TTS settings (engine, language, voice);
  - "Choose speech engine" — list of installed engines, the choice is saved in the app.
- You can test the voice with the "Test voice" button.

### Background & reliability
- Foreground service (`specialUse`): the button and playback work outside the activity.
- Persistent notification (option "Keep in notifications") with three buttons:
  show/hide the floating button, start/stop reading, speed.
- Auto-start after reboot.
- Prompt to disable battery optimization so the service isn't killed.
- "Continue where left off": the position is kept between runs.

### Privacy
- No internet, analytics or ads — text is processed on the device.
- AccessibilityService is used only to read the selection/clipboard.

### UI languages
- 20+ languages: Russian, English, Spanish, German, French, Italian, Portuguese, Dutch, Polish, Turkish, Arabic, Persian, Hindi, Bengali, Urdu, Chinese (Simplified), Japanese, Korean, Vietnamese, Thai, Indonesian.

## How it works

Text from another app is read by the Accessibility service (`SelectionAccessibilityService`):

1. Selection cache from the `TYPE_VIEW_TEXT_SELECTION_CHANGED` event.
2. Fallback: current selection of the focused node (`textSelectionStart/End`).
3. Fallback: invisible copy of the selection via `ACTION_COPY`, then reading the clipboard.

Important: Android 10+ forbids reading the clipboard for an app without focus. So the service
briefly raises a 1×1 transparent focusable window of type `TYPE_ACCESSIBILITY_OVERLAY`,
reads the clipboard and removes the window — that's how Boltun legally gets text even with manual copying.

The floating button and playback live in a foreground service (`FloatingService`, type `specialUse`).
TTS is the system engine (`TtsController`); language/voice use the engine default.

> The reference T2S has no AccessibilityService — it requires manual copying.
> Here AccessibilityService is required: it captures the selection and reads the clipboard.

## Build requirements

- JDK 17
- Android SDK with `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`
- Gradle 8.9 (wrapper included)

In `local.properties` set the SDK path:

```
sdk.dir=/path/to/android-sdk
```

## Build

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew bundleRelease
```

- Debug: `app/build/outputs/apk/debug/boltun_v<version>.apk`
- Release: `app/build/outputs/apk/release/boltun_v<version>.apk`
- AAB: `app/build/outputs/bundle/release/app-release.aab`

Signing parameters — in `keystore.properties` (the file and the key are not committed).

## Install

```bash
adb install -r app/build/outputs/apk/release/boltun_v1.1.0.apk
```

## Structure

```
app/src/main/java/ru/boltun/app/
  MainActivity.kt                  — permission onboarding, voice test, engine selection
  FloatingService.kt               — foreground + overlay button and menu
  TtsController.kt                 — TextToSpeech wrapper (queue, pause, speed, seeking)
  SelectionAccessibilityService.kt — selection capture
  SpeakActivity.kt                 — PROCESS_TEXT / ACTION_SEND
  BootReceiver.kt                  — auto-start
  Prefs.kt                         — settings
```

## Known limitations

- Text retrieval order: selection cache → focused node selection → `ACTION_COPY` + clipboard → plain clipboard.
- Telegram and similar apps with custom views don't expose the selection via a11y: it isn't captured there.
  Workaround — copy the message (long press → "Copy") and tap the floating button: the clipboard is used.
- In some apps (certain WebView/browsers, OEM apps) the selection isn't exposed either — copying via `ACTION_COPY` or manually copied text works.
- If there is neither a selection nor the clipboard — "Nothing selected" is shown.
- Because of the AccessibilityService permission, the app is not intended for Google Play.

## License

Boltun is distributed under the **GNU General Public License v3.0**.
Full text — in the [LICENSE](LICENSE) file.

Copyright (C) 2026 Tar3ah.

## Publishing to GitHub

```bash
git remote add origin https://github.com/TAPZAH/boltun.git
git branch -M main
git push -u origin main
```

Release: build `assembleRelease`/`bundleRelease` and attach the APK/AAB to a GitHub Release.
The signing key (`keystore/`) and `keystore.properties` are intentionally excluded from the repository.

## CI/CD (GitHub Actions)

- `.github/workflows/build.yml` — on push to `main`, pull request and manually builds the debug APK and uploads the artifact.
- `.github/workflows/release.yml` — on a `v*` tag (or manually) builds APK/AAB and publishes a GitHub Release.

For a signed release add in **Settings → Secrets and variables → Actions**:

- `KEYSTORE_BASE64` — keystore in base64: `base64 -w0 keystore/boltun.jks`
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

If secrets are not set, the release build still runs but the APK is unsigned.

Release:

```bash
git tag v1.1.0
git push origin v1.1.0
```

---

# Русский

Android-приложение: озвучивает выделенный и скопированный текст по нажатию плавающей кнопки поверх других приложений.

Автор: **Tar3ah**

## Возможности

### Озвучка текста
- **Плавающая кнопка** поверх любых приложений: перетаскивается, позиция сохраняется.
- **Выделил → тапнул → заговорило.** Захват выделения без ручного «Копировать». Пока срабатывает не везде; для гарантии лучше предварительно копировать.
- Текст разбивается на предложения и читается по порядку; интерфейс показывает позицию `7 / 42`.
- Если выделение недоступно (некоторые WebView/OEM-приложения), используется буфер обмена.

### Меню управления (двойной тап по кнопке)
- Кнопки ⏮ назад · ⏯ старт/пауза · ⏹ стоп · ⏭ вперёд · скорость. Перемотка предложениями.
- Вторая строка: ↻ переиграть с начала · 🕓 продолжить с места · ⚙ приложение/настройки · ✕ закрыть.
- Счётчик предложений, обновляется по ходу чтения.
- Меню появляется автоматически во время озвучки и встаёт с той стороны, где есть место.
- Долгое нажатие на кнопку — стоп.

### Способы запуска
- Выделение текста + плавающая кнопка — основной сценарий. Работает не во всех приложениях, см. «Известные ограничения».
- Надёжнее: выделить → «Копировать» → нажать плавающую кнопку (сработает буфер обмена).
- «Поделиться» — отправить текст в Boltun из другого приложения.
- В шторке уведомлений нажать кнопку пуск.

### Синтез речи
- Системный TTS-движок по умолчанию или любой установленный (список движков в приложении).
- Скорости: 0.75 · 0.90 · 1.00 · 1.10 · 1.25 · 1.50 · 2.00.
- Язык и голос наследуются от выбранного движка.
- Рекомендуемый синтез для русского языка: [ruvoice-tts](https://github.com/kost-t-human/ruvoice-tts).

## Первый запуск

1. «Разрешить показ поверх других окон» — открывает системные настройки.
2. «Включить службу Специальные возможности» — включите **Boltun**.
3. «Разрешить уведомления» (Android 13+).
4. «Отключить оптимизацию батареи» — чтобы сервис не убивался.
5. «Показать плавающую кнопку».

## Использование

- Выделите текст в любом приложении и нажмите плавающую кнопку (одиночный тап).
- **Двойной тап** по кнопке — открыть/закрыть меню управления.
- Меню (появляется автоматически во время озвучки):
  - счётчик предложений `7 / 42` (обновляется по ходу чтения);
  - верхняя строка: ⏮ назад, ⏯ старт/пауза, ⏹ стоп, ⏭ вперёд, скорость;
  - нижняя строка: ↻ переиграть с начала, 🕓 продолжить с места, ⚙ приложение, ✕ закрыть.
- Долгое нажатие на кнопку — стоп.
- Кнопку можно перетаскивать; позиция запоминается, меню автоматически встаёт с той стороны, где есть место.
- Через меню выделения можно выбрать «Boltun» (`PROCESS_TEXT`) или «Поделиться».
- Кнопка «Проверить захват выделения» показывает, что удалось получить и из какого источника
  (`cache` — из события выделения, `node` — из узла, `copy` — через копирование, `clipboard` — из буфера).
- На экране показывается текущий **синтез**, есть две кнопки:
  - «Синтез по умолчанию» — системные настройки TTS (движок, язык, голос);
  - «Выбрать движок синтеза» — список установленных движков, выбор сохраняется в приложении.
- Голос можно проверить кнопкой «Проверить голос».

### Надёжность и фон
- Foreground-сервис (`specialUse`): кнопка и воспроизведение работают вне активности.
- Постоянное уведомление (опция «Держать в уведомлениях») с тремя кнопками:
  показать/скрыть плавающую кнопку, старт/стоп озвучки, скорость.
- Автозапуск после перезагрузки.
- Подсказка отключить оптимизацию батареи, чтобы сервис не убивался.
- «Продолжить с места»: позиция сохраняется между запусками.

### Приватность
- Без интернета, аналитики и рекламы — текст обрабатывается на устройстве.
- AccessibilityService используется только для чтения выделения/буфера.

### Языки интерфейса
- 20+ языков: русский, английский, испанский, немецкий, французский, итальянский, португальский, нидерландский, польский, турецкий, арабский, персидский, хинди, бенгальский, урду, китайский (упр.), японский, корейский, вьетнамский, тайский, индонезийский.

## Как работает

Текст из чужого приложения читает служба «Специальные возможности» (`SelectionAccessibilityService`):

1. Кэш выделения из события `TYPE_VIEW_TEXT_SELECTION_CHANGED`.
2. Fallback: текущее выделение фокус-ноды (`textSelectionStart/End`).
3. Fallback: невидимое копирование выделения через `ACTION_COPY`, затем чтение буфера.

Важно: Android 10+ запрещает читать буфер обмена приложению без фокуса. Поэтому служба
на короткое время поднимает 1×1 прозрачное фокусное окно `TYPE_ACCESSIBILITY_OVERLAY`,
читает буфер и убирает окно — так Boltun легально получает текст и при ручном копировании.

Плавающая кнопка и воспроизведение живут в foreground-сервисе (`FloatingService`, тип `specialUse`).
TTS — системный движок (`TtsController`), язык/голос — по умолчанию движка.

> В референсном T2S нет AccessibilityService — он требует ручного копирования.
> Здесь AccessibilityService обязателен: он и захватывает выделение, и дочитывает буфер.

## Сборка

- JDK 17
- Android SDK с `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`
- Gradle 8.9 (wrapper уже в комплекте)

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew bundleRelease
```

- Debug: `app/build/outputs/apk/debug/boltun_v<version>.apk`
- Release: `app/build/outputs/apk/release/boltun_v<version>.apk`
- AAB: `app/build/outputs/bundle/release/app-release.aab`

Параметры подписи — в `keystore.properties` (файл и сам ключ в репозиторий не входят).

## Установка

```bash
adb install -r app/build/outputs/apk/release/boltun_v1.1.0.apk
```

## Структура

```
app/src/main/java/ru/boltun/app/
  MainActivity.kt                  — онбординг разрешений, тест голоса, выбор движка
  FloatingService.kt               — foreground + overlay кнопка и меню
  TtsController.kt                 — обёртка TextToSpeech (очередь, пауза, скорость, перемотка)
  SelectionAccessibilityService.kt — захват выделения
  SpeakActivity.kt                 — PROCESS_TEXT / ACTION_SEND
  BootReceiver.kt                  — автозапуск
  Prefs.kt                         — настройки
```

## Известные ограничения

- Способы получения текста по порядку: кэш выделения → выделение фокус-ноды → `ACTION_COPY` + чтение буфера → просто буфер обмена.
- Telegram и подобные приложения со своими view не отдают выделение через a11y: выделенное там не захватывается.
  Рабочий путь — скопировать сообщение (долгое нажатие → «Копировать») и нажать плавающую кнопку: сработает буфер.
- В части приложений (некоторые WebView/браузеры, OEM-приложения) выделение тоже не отдаётся — работает копирование через `ACTION_COPY` или уже скопированный вручную текст.
- Если ни выделения, ни буфера нет — показывается «Ничего не выделено».
- Из-за разрешения AccessibilityService приложение не предназначено для Google Play.

## Лицензия

Boltun распространяется под лицензией **GNU General Public License v3.0**.
Полный текст — в файле [LICENSE](LICENSE).

Copyright (C) 2026 Tar3ah.

## Публикация на GitHub

```bash
git remote add origin https://github.com/TAPZAH/boltun.git
git branch -M main
git push -u origin main
```

Релиз: соберите `assembleRelease`/`bundleRelease` и приложите APK/AAB к GitHub Release.
Ключ подписи (`keystore/`) и `keystore.properties` намеренно исключены из репозитория.

## CI/CD (GitHub Actions)

- `.github/workflows/build.yml` — на push в `main`, pull request и вручную собирает debug APK и прикладывает артефакт.
- `.github/workflows/release.yml` — на тег `v*` (или вручную) собирает APK/AAB и публикует GitHub Release.

Для подписанного релиза добавьте в **Settings → Secrets and variables → Actions**:

- `KEYSTORE_BASE64` — keystore в base64: `base64 -w0 keystore/boltun.jks`
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Если секреты не заданы, release-сборка пройдёт, но APK будет неподписанным.

Выпуск релиза:

```bash
git tag v1.1.0
git push origin v1.1.0
```
