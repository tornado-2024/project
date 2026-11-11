# Tornado Control (Android)

Приложение-обёртка: получает по сети **указатель** на индексную страницу, показывает её
как содержимое приложения, умеет работать офлайн и предоставляет JavaScript-мост в Android.

## Схема работы

```
settings.json ──► удалённые настройки (JSON)
control       ──► указатель (URL) ──► индексная страница  (показывается)
password      ──► хэш пароля (защищает раздел настроек)
оффлайн      ──► сохранённая страница 404 (или встроенная заглушка)
```

По умолчанию:

| Что | Адрес |
|---|---|
| указатель (control) | `https://raw.githubusercontent.com/tornado-2024/project/refs/heads/main/control` |
| удалённые настройки | `https://raw.githubusercontent.com/tornado-2024/project/refs/heads/main/settings.json` |
| хэш пароля | `https://raw.githubusercontent.com/tornado-2024/project/refs/heads/main/password` |

Сейчас `control` отдаёт `https://tornado-2024.github.io/site/` — эта страница и открывается
в приложении. Ответ `control` может быть:
* plain-текст с URL — `https://example.com/`;
* JSON — `{"url": "https://example.com/"}` (ключи: `url`, `index`, `indexUrl`, `link`, `page`, `href`, `target`, `site`);
* HTML с `<meta http-equiv="refresh" content="0;url=...">`.

> `raw.githubusercontent.com` кэширует файл по URL на несколько минут — после правки
> `settings.json` приложение может какое-то время видеть старую версию.

## Удалённые настройки (JSON)

Файл `settings.json` (если его нет — работают значения по умолчанию):

```json
{
  "controlUrl":      "https://raw.githubusercontent.com/tornado-2024/project/refs/heads/main/control",
  "indexUrl":        "",
  "errorPageUrl":    "",
  "passwordUrl":     "https://raw.githubusercontent.com/tornado-2024/project/refs/heads/main/password",
  "settingsEnabled": false,
  "requirePassword": true,
  "title":           "Tornado",
  "showHeader":      true,
  "refreshOnStart":  true,
  "cacheEnabled":    true,
  "timeoutMs":       15000,
  "theme":           "dark",
  "statusBarColor":  "#171B24",
  "accentColor":     "#4C8DFF",
  "userAgent":       ""
}
```

| Ключ | Смысл |
|---|---|
| `controlUrl` | откуда берётся URL индекса (указатель) |
| `indexUrl` | если задан — открывается сразу, без запроса `control` |
| `errorPageUrl` | адрес страницы 404 (кэшируется для офлайна); пусто → `index + errors/404/`, иначе `control + /errors/404/` |
| `passwordUrl` | где лежит хэш пароля от раздела настроек |
| `settingsEnabled` | показывать ли кнопку ⚙ в шапке (**по умолчанию false — раздел скрыт**) |
| `requirePassword` | требовать ли пароль при открытии настроек |
| `title` | название страницы в верхней строке (приоритетнее `<title>` страницы) |
| `showHeader` | показывать ли верхнюю панель целиком |
| `refreshOnStart` | обновлять контент при запуске |
| `cacheEnabled` | кэшировать страницы на диск |
| `timeoutMs` | таймаут сетевых запросов |
| `theme` | `dark` / `light` |
| `statusBarColor`, `accentColor` | цвета интерфейса |
| `userAgent` | свой User-Agent для запросов |

Настройки сохраняются локально, поэтому применяются и офлайн.

## Раздел настроек

По умолчанию кнопка ⚙ **скрыта** (`settingsEnabled: false`). Два способа попасть в настройки:

1. **Скрытый вход** — 5 быстрых тапов по заголовку в верхней панели.
2. **Удалённое включение** — `settingsEnabled: true` включает кнопку ⚙ в шапке.

Если существует файл `password` (то есть `passwordUrl` отвечает 2xx) и `requirePassword: true`,
то перед открытием настроек запрашивается пароль. Файл может содержать:

```
<64 hex-символа>          # sha256 от пароля
sha256:<hex>               # то же, но с явным алгоритмом
sha1:<hex>                # 40 hex-символов
md5:<hex>
plain:<пароль открытым текстом>
```

Сравнение регистронезависимое, для hex — по digest. Если файла `password` нет, пароль не
запрашивается.

В самом разделе можно локально переопределить адреса (`control`, `settings.json`), заголовок,
всегда показывать кнопку настроек и очистить кэш.

## JS → Android мост

В странице доступны `window.Android` и `window.Native`, а также обёртка
`window.Tornado` / `window.TC` (`assets/app/bridge.js`, подставляется после загрузки).

### Вибрация
| Вызов | Описание |
|---|---|
| `Android.vibrate(ms)` | одиночная вибрация, мс |
| `Android.vibratePattern("[0,40,80,40]", -1)` | паттерн (JSON-массив мс), `repeat = -1` — бесконечно |
| `Android.vibrateEffect('heavy')` | эффекты: `click`, `tick`, `heavy`, `notification`, `success`, `error` |
| `Android.cancelVibration()` | остановить |
| `Android.hasVibrator()` | есть ли вибромотор |

### Интерфейс и система
| Вызов | Описание |
|---|---|
| `Android.toast(text)` | Toast |
| `Android.setTitle(title)` | заголовок верхней панели |
| `Android.setStatusBarColor('#10131A')` | цвет статус-бара |
| `Android.openUrl(url)` | открыть в системном браузере |
| `Android.copy(text)` | буфер обмена |
| `Android.share(text, title)` | «Поделиться» |
| `Android.notify(title, text)` | системное уведомление (Android 13+ нужен `POST_NOTIFICATIONS`) |

### Состояние
| Вызов | Описание |
|---|---|
| `Android.getDeviceInfo()` | JSON: модель, Android, sdk, версия, `online`, адреса, размер кэша |
| `Android.getSettings()` | действующие удалённые настройки (JSON) |
| `Android.getIndexUrl()` | URL индексной страницы |
| `Android.isOnline()` | есть ли сеть |
| `Android.getControlUrl()` / `getErrorPageUrl()` | адреса |
| `Android.reload()` / `refresh()` | перезагрузка / обновление с сервера |
| `Android.showSettings()` | открыть настройки (с паролем, если включено) |
| `Android.log(msg)` | запись в logcat (тег `TornadoBridge`) |
| `Android.exitApp()` | закрыть приложение |

```html
<button onclick="Android.vibrate(150)">Вибрация</button>
<script>console.log(JSON.parse(Android.getSettings()));</script>
```

## Скрипты

### `tools/setup_git.sh` — автонастройка git + GitHub

```bash
bash tools/setup_git.sh --check                       # текущее состояние
bash tools/setup_git.sh --repo https://github.com/tornado-2024/project.git --login --push
bash tools/setup_git.sh --repo git@github.com:tornado-2024/project.git --ssh
```

Что делает:
* ставит `gh` (GitHub CLI), если его нет;
* пишет `~/.gitconfig` через `git config --global` (старый файл сохраняется как `~/.gitconfig.bak-*`);
* определяет GitHub-ник прямо из URL репозитория и подставляет noreply-email
  вида `<id>+<ник>@users.noreply.github.com` (id берётся с api.github.com);
* авторизует через PAT (`gh auth login`, токен вводится в терминале) либо генерирует
  SSH-ключ и печатает публичную часть;
* добавляет/создаёт `remote origin` (`--create` создаёт репозиторий на GitHub);
* делает коммит и пушит (`--push`).

Ключи: `--name --email --login --repo --protocol --token-file --branch --visibility
--create --public --ssh --no-login --push --message --check`.

### `tools/publish_repo_files.sh` — файлы для работы приложения

```bash
bash tools/publish_repo_files.sh --title "Tornado" --random-password
bash tools/publish_repo_files.sh --password "мой-пароль" --site-dir ~/site
```

Создаёт:
* `settings.json` — удалённые настройки (controlUrl, title, passwordUrl, показ раздела настроек…);
* `password` — sha256-хэш пароля (сам пароль в репозиторий не попадает; при
  `--random-password` пароль печатается один раз);
* `errors/404/index.html` — страница 404 (по умолчанию в текущем каталоге,
  `--site-dir` — в репозиторий сайта `tornado-2024/site`).

## Структура проекта

```
TornadoControl/
├── build.sh                       сборка без Gradle (aapt2 -> javac -> d8 -> apksigner)
├── build.gradle, settings.gradle  вариант для Android Studio / CI
├── keystore/control.jks           ключ подписи (генерируется автоматически)
├── tools/
│   └── strip_method_params.py     чинит совместимость javac 21 + d8
└── app/src/main/
    ├── AndroidManifest.xml
    ├── assets/app/
    │   ├── bridge.js              JS-обёртка над нативным мостом
    │   ├── demo.html              демо-страница моста (работает офлайн)
    │   └── offline.html           заглушка, когда нет сети и нет кэша
    ├── java/com/tornado/control/
    │   ├── MainActivity.java      WebView, загрузка, кэш, UI, настройки
    │   ├── JsBridge.java          @JavascriptInterface — мост в Android
    │   ├── RemoteSettings.java    удалённые настройки (JSON)
    │   ├── Pointer.java           разбор control-указателя и проверка хэша
    │   ├── HttpClient.java        HTTP-запросы (без внешних зависимостей)
    │   ├── CacheStore.java        дисковый кэш (control, индекс, 404, подресурсы)
    │   ├── Net.java               проверка сети
    │   └── Prefs.java             локальные настройки и кэш последних удалённых
    └── res/                       тема, layout, иконки
```

## Сборка

### Termux (без Gradle, работает на aarch64)

```bash
pkg install aapt2 apksigner d8
# android.jar: platforms/android-33 (API 33)
# d8 из build-tools 36+ (умеет javac 21 без вырезания атрибутов)

bash build.sh              # -> app.apk          release, targetSdk 33
bash build.sh --debug      # -> app-debug.apk    android:debuggable=true + логи в файл
bash build.sh --android10  # -> app-android10.apk  targetSdk 29 (Android 10)
bash build.sh out.apk      # свой путь
```

Все варианты имеют `minSdk 24` (Android 7+) и подписываются одним ключом, поэтому
ставить их можно друг поверх друга.

Шаги: `aapt2 compile` → `aapt2 link` → `javac --release 8` →
`d8` (при необходимости — с вырезанием `MethodParameters` для старых d8) →
`jar` → `apksigner` (v2+v3).

> Важно: dex собирается **без модификаций** class-файлов. Раньше атрибут
> `MethodParameters` вырезался скриптом, и такой dex проходил проверку d8, но
> падал на ART-верификаторе при установке release-сборки
> (`INSTALL_FAILED_DEXOPT` → «Приложение не установлено»).

### Android Studio / Gradle

```bash
./gradlew assembleRelease
```

Внешних зависимостей нет — только `android.webkit`, `android.os` и т.п.

## Настройка приложения (имя, иконка, URL, ключ)

Отдельный инструмент — `tools/app_config.sh`:

```bash
bash tools/app_config.sh            # интерактивное меню
bash tools/app_config.sh --show     # текущая конфигурация
```

| Ключ | Что меняет | Где лежит результат |
|---|---|---|
| `--name "Текст"` | имя приложения в лаунчере | `res/values/strings.xml` |
| `--icon файл.png` | иконка приложения | `res/mipmap-*/ic_launcher.png` + манифест |
| `--icon файл.xml` | иконка-вектор | `res/drawable/ic_launcher.xml` + манифест |
| `--icon-make "#4C8DFF"` | сгенерировать иконку заданным цветом | через `tools/make_icon.py` |
| `--control URL` | адрес control-указателя | `settings.json` **и** `RemoteSettings.DEFAULT_CONTROL_URL` |
| `--index URL` | адрес индексной страницы | `settings.json` (`indexUrl`) |
| `--settings-url URL` | адрес файла настроек | `settings.json` + `DEFAULT_SETTINGS_URL` |
| `--error-url URL` | адрес страницы 404 | `settings.json` (`errorPageUrl`) |
| `--password-url URL` | адрес файла с хэшем пароля | `settings.json` + `DEFAULT_PASSWORD_URL` |
| `--title "Текст"` | **текст верхней панели внутри приложения** | `settings.json` (`title`) |
| `--new-key --key-pass ПАРОЛЬ` | новый ключ подписи | `keystore/control.jks` (старый → `.bak-*`) |
| `--package com.my.app` | идентификатор приложения | манифест, `app/build.gradle`, пакеты Java |
| `--version 2.0 --version-code 7` | версия | `AndroidManifest.xml` |
| `--build` / `--build-all` | пересборка APK | `app.apk`, `app-debug.apk`, `app-android10.apk` |

Примеры:

```bash
bash tools/app_config.sh --name "Tornado" --icon ~/Pictures/logo.png --build
bash tools/app_config.sh --control https://example.com/control \
                          --index https://example.com/ --title "Мой сайт" --build
bash tools/app_config.sh --new-key --key-pass secret --build
```

**Важно различать три разных «имени»:**

1. **Имя в лаунчере** (под иконкой) — `app_name` в `strings.xml` (`--name`).
2. **Заголовок верхней панели внутри приложения** — удалённая настройка `title`
   в `settings.json` (`--title` или руками на GitHub), либо локально в настройках.
3. **`<title>` страницы** — то, что видно в браузере; приложение подставляет
   свой заголовок, если задан `title`.

При смене ключа подписи старое приложение нужно **удалить** с телефона, иначе
установка упадёт с конфликтом подписи.

### Все настройки (`settings.json`)

| Ключ | Тип | Смысл |
|---|---|---|
| `controlUrl` | строка | адрес control-указателя (откуда берётся URL индекса) |
| `settingsUrl` | строка | адрес самого файла настроек |
| `indexUrl` | строка | индекс напрямую (минуя control) |
| `errorPageUrl` | строка | страница 404 для офлайна |
| `passwordUrl` | строка | файл с sha256-хэшем пароля |
| `settingsEnabled` | bool | показывать кнопку ⚙ (после загрузки настроек с сервера) |
| `requirePassword` | bool | спрашивать пароль перед настройками |
| `title` | строка | текст верхней панели внутри приложения |
| `showHeader` | bool | показывать верхнюю панель |
| `refreshOnStart` | bool | обновлять контент при запуске |
| `cacheEnabled` | bool | кэшировать страницы на диск |
| `timeoutMs` | число | таймаут запросов, мс |
| `theme` | строка | `dark` / `light` / `system` |
| `statusBarColor` | строка | цвет статус-бара |
| `accentColor` | строка | акцентный цвет |
| `userAgent` | строка | свой User-Agent |
| `iconVariant` | строка | `default` / `light` / `dark` / `beta` / `all` |
| `fullscreen` | bool | полноэкранный режим (скрыть статус-бар системы) |
| `keepScreenOn` | bool | не гасить экран |
| `allowZoom` | bool | разрешить pinch-zoom страницы |
| `openLinksExternally` | bool | внешние ссылки открывать в браузере |
| `refreshIntervalSec` | число | автообновление по таймеру, сек (0 — выкл) |
| `maxCacheSizeMb` | число | лимит кэша, МБ (0 — 64) |
| `retryCount` | число | повторов запроса при сетевой ошибке |
| `hideStatusLine` | bool | скрыть нижнюю строку статуса приложения |
| `enableBridge` | bool | разрешить JS-мост `window.Android` |
| `allowedHosts` | строка | белый список доменов через запятую (пусто — все) |

Приоритет: **локальные значения (из настроек приложения или страницы) > удалённые > сохранённые > по умолчанию**.

## Проверка возможностей

Страница `selftest.html` проверяет весь мост и лежит:

* в assets приложения → открыть через настройки (⚙ → адрес
  `file:///android_asset/app/selftest.html`);
* на сайте → https://tornado-2024.github.io/site/selftest.html

Кнопка «Автотест» последовательно вызывает все методы `window.Android`
(вибрация всех видов, toast, заголовок, цвет статус-бара, буфер обмена,
уведомления, `getDeviceInfo`, `getSettings`, `isOnline` и т.д.) и показывает
сводку «OK / нет» по каждому пункту. Опасные вызовы (`showSettings`, `refresh`,
`reload`, `exitApp`) вынесены в отдельные кнопки.

## Установка

```bash
adb install -r app.apk
```

или открыть `app.apk` из «Загрузок» на телефоне.

## Отладка

```bash
adb logcat -s TornadoControl TornadoBridge chromium
```
