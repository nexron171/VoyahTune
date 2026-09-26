# Подготовка окружения и выпуск VoyahTune

Актуально на 27 сентября 2026 года. Команды выполняются из корня репозитория.
`3.13.0` в примерах замените номером своего релиза.

## 1. Выбрать формат релиза

| Формат | Команда | Результат |
| --- | --- | --- |
| По старому, со скриптами | `./make_release.sh 3.13.0` | Отдельные Full и Light ZIP с install/remove |
| Только единый payload | `./make_release.sh 3.13.0 --payload` | `payload_3.13.0.zip` и заготовка записи каталога |
| Payload и GUI macOS | `./make_release.sh 3.13.0 --mac` | Payload ZIP и самостоятельный Universal GUI ZIP |
| Только установщик macOS | `./Installer/scripts/build-all-macos.sh --mac` | Universal GUI без сборки Android |

Без флагов используется классический формат. `--installers` собирает payload и GUI
для всех платформ; флаги `--mac`, `--windows`, `--linux` ограничивают платформы.
Windows ARM/Linux ARM не собираются. Текущая переработка проверяется только на macOS;
Windows и реальное ГУ — отдельная последующая сессия.

GUI пересобирается только при изменении его логики. Обычный новый релиз — общий payload
и обновление каталога. ADB и небольшой remover входят в GUI, APK скачиваются/импортируются
из GUI. Установщик и VoyahTune имеют независимые версии. Пользовательского CLI нет.
Windows-пакет включает offline WebView2; Linux требует X11/XWayland и glibc Ubuntu 22.04.

Готовые архивы/бинарники хранятся в игнорируемом `Releases/`. В Git хранится spec и
[каталог](../Installer/releases/index.json); запись каталога генерируется по готовому payload.
[Контракты форматов и runtime-режимов](installer-protocol.md).

## 2. Один раз подготовить окружение

### 2.1. Выбрать сборочную машину

Основной путь выпуска обоих форматов — **macOS**. Старый shell-сценарий можно
запускать также в Linux с Android SDK. Оркестратор нового релиза
`make_release.sh --installers` сейчас требует macOS: Windows/Linux он собирает
в подготовленных Linux-контейнерах Colima.

На Windows/Linux можно нативно пересобрать установщик своей ОС без Android-сборки:
см. [Installer/BUILDING.md](../Installer/BUILDING.md). Это отдельная сборка desktop,
а не запуск полного macOS-оркестратора.

### 2.2. Общая среда для Android APK и старого формата

Установите:

- Git, POSIX shell и Bash, `zip`, `unzip`, стандартные Unix-утилиты;
- Node.js 22 с npm: Node нужен и старому формату для проверок JavaScript;
- JDK 21;
- Android SDK с Command-line Tools. Gradle отдельно не нужен: проекты используют
  свои `gradlew` и закреплённый Gradle 8.13.

На macOS сначала установите Xcode Command Line Tools:

```sh
xcode-select --install
```

В Android Studio откройте SDK Manager и установите Android SDK Command-line Tools.
Затем задайте пути и установите пакеты SDK; пример для стандартного пути macOS:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

sdkmanager "platforms;android-35" "build-tools;35.0.0" \
  "ndk;27.0.12077973" "cmake;3.22.1" "platform-tools"
sdkmanager --licenses
```

Если SDK расположен иначе, укажите свой путь. В Linux задайте путь установленного
JDK 21 в `JAVA_HOME` и SDK в `ANDROID_HOME`; `/usr/libexec/java_home` — только macOS.
Сохраните переменные в конфигурации shell для следующих запусков терминала.

Вместо `ANDROID_HOME` Gradle может читать `sdk.dir=/абсолютный/путь/к/sdk` из
**обоих** файлов `Native/local.properties` и `RestoreMode/local.properties`.
Пути с другого компьютера необходимо исправить. Эти файлы игнорируются Git.

Проверьте окружение:

```sh
java -version
node --version
test -s Native/app/lib/android.car.jar
(cd Native && ./gradlew --version)
(cd RestoreMode && ./gradlew --version)
```

В выводе Gradle проверьте используемую JVM. `Native/app/lib/android.car.jar` —
необходимый входной файл проекта. Rust, Tauri, Python и Docker для старого формата
не нужны.

### 2.3. Дополнительно для GUI macOS

Установите Python 3.12+ и Rust через rustup. Версия Rust закреплена в
`Installer/rust-toolchain.toml`, сейчас это `1.98.1`:

```sh
rustup toolchain install 1.98.1 --profile minimal --component rustfmt,clippy
rustup target add --toolchain 1.98.1 aarch64-apple-darwin x86_64-apple-darwin
python3 --version
npm --version
./Installer/scripts/build-all-macos.sh --check --mac
```

Desktop-зависимости устанавливаются автоматически через `npm ci` во время сборки.
Глобально устанавливать Tauri CLI не требуется.

Теперь можно выполнять `./make_release.sh 3.13.0 --mac`. При выборе только macOS
Docker/Colima не проверяются и не запускаются.

### 2.4. Дополнительно для Windows/Linux из macOS

Установите Docker CLI, Colima и `rsync`. Один раз подготовьте:

1. Colima profile `v` с `COLIMA_HOME=<repo>/Releases/cache/colima`;
2. постоянный контейнер `vti-windows` с cargo-xwin и Windows x64 target;
3. постоянный контейнер `vti-linux-amd64` архитектуры x86-64;
4. на Apple Silicon — Rosetta в Colima и закреплённый файл linuxdeploy в кэше.

Команды приведены в разделе
[подготовки Colima с нуля](../Installer/BUILDING.md#подготовка-colima-и-контейнеров-с-нуля).
Общий скрипт запускает готовые контейнеры, но **не создаёт** их. Одна установка
Docker Desktop не заменяет подготовку этой среды.

После подготовки:

```sh
./Installer/scripts/build-all-macos.sh --check
```

Для выбранных платформ можно использовать `--check --windows --linux`.
Проверка может запустить подготовленную VM/контейнеры; компиляции она не выполняет
и Android SDK не проверяет. Первая сборка скачивает недостающие зависимости,
ADB и упаковочные инструменты. После загрузки payload установка доступна без сети.

### 2.5. Что переносить на другой компьютер

Кроме checkout, сохраните **действующий** `~/.android/debug.keystore`.
Сейчас release-сборки Native и RestoreMode используют debug signing config.
Без этого файла новый компьютер сгенерирует другой ключ.

При известном несовпадении подписи GUI удаляет соответствующее приложение
с данными и устанавливает заново; для Native есть промежуточная перезагрузка.
Классические скрипты этого механизма не получили: установка поверх APK с другой
подписью завершится ошибкой Android. Для обновления с сохранением данных продолжайте
использовать один ключ. Потерянный ключ нельзя восстановить из APK.

`local.properties` настройте заново. SDK, npm/Gradle/Rust-кэши и контейнеры можно
восстановить; `Releases/cache/` можно перенести для ускорения. При смене пути
checkout контейнеры нужно пересоздать с правильным mount `/work`.
Ключи и готовые сборки в Git не добавляйте.

## 3. Подготовить содержимое релиза

### Исходники и файлы комплекта

| Что меняется | Где редактировать |
| --- | --- |
| Native / RestoreMode | `Native/app/src/`, `RestoreMode/app/src/` |
| Hooks и конфигурации | `Packaging/inject/` |
| Loader, boot RC/SH, permission whitelist | `Packaging/system/` |
| Frida и готовые инструменты | `Packaging/tools/` |
| DNS helper / готовый overlay APK | `Packaging/installer/common/`, `Packaging/vendor-overlay/` |
| Классические установка/удаление | `Packaging/installer/full/`, `Packaging/installer/light/` |
| Исполняемый процесс GUI | `Installer/crates/installer-core/src/engine.rs` |
| Команды автомобиля / отображаемые шаги | `classic_commands.rs`, `plans.rs` в той же папке |
| Интерфейс | `Installer/desktop/src/` |
| Общая иконка | `Packaging/branding/app-icon.png` |
| Описание изменений / инструкция пользователю | `hownews.md`, `Packaging/README.txt` |

Новая сборка подхватывает изменения существующих файлов. Frida и DNS overlay
берутся из Packaging готовыми. Для замены DNS APK следуйте
[инструкции overlay](../Packaging/vendor-overlay/README.md), включая закреплённые суммы.
Иконки экспортируются отдельно: `node Installer/scripts/generate-icons.mjs`;
зависимости описаны в [инструкции branding](../Packaging/branding/README.md).

При добавлении собственного `.js/.json` GUI-сборщик создаёт действие recipe автоматически.
В `Packaging/installer/payload-spec.json` укажите особые режимы/пути/права и добавьте
устаревшие пути в накопительный `removeFiles`. Классические `.sh/.bat` обновляются отдельно.
Новая системная роль/операция требует изменения core, capability и версии GUI.
[Карта сохранённых специальных процедур](installer-classic-port.md).

`manifest.json` и другие JSON внутри сборочного payload создаются автоматически:
их не правят вручную. Не редактируйте `Releases/` вместо исходников.

### Версии и подписи

1. Выберите SemVer-версию комплекта, например `3.13.0`. Она передаётся аргументом
   `make_release.sh`; `@VERSION@` в исходных скриптах заменяется автоматически.
2. Для изменившихся APK увеличьте `versionCode` относительно опубликованного
   выпуска и задайте `versionName` в `Native/app/build.gradle.kts` и
   `RestoreMode/app/build.gradle.kts`. Номер комплекта сам эти поля не меняет.
3. При изменениях движка обновите `workspace.package.version` в
   `Installer/Cargo.toml` и записи локальных пакетов в `Installer/Cargo.lock`.
   Это единая версия GUI/движка и desktop-пакетов, независимая от payload.
4. Обновите описание изменений. Проверьте diff и сохраните готовые исходники
   в коммите перед распространяемой сборкой.

Новый формат записывает версию комплекта и Git revision в подписанные метаданные
APK. Изменённое рабочее дерево даёт revision с `-dirty`; сборка это допускает.
Старый формат вызывает Gradle без этих release-параметров: его APK не являются
готовой заменой для GUI-режима `--no-build`.

Проверка сохранения ключа:

```sh
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs /path/to/previous.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs /path/to/new.apk
```

Сравните SHA-256 сертификата отдельно для Native и RestoreMode.

## 4. Собрать по старому: ZIP со скриптами

```sh
./make_release.sh 3.13.0
```

Скрипт запускает проверки Packaging, собирает единые APK обоих приложений,
раскладывает файлы и создаёт:

```text
Releases/dist/VoyahTune-3.13.0.zip
Releases/dist/VoyahTune-3.13.0-light.zip
Releases/build/VoyahTune-3.13.0/
Releases/build/VoyahTune-3.13.0-light/
```

В каждом ZIP — плоская папка с APK, ресурсами, `install.sh/.bat`, `remove.sh/.bat`
и README. Windows ADB входит в старый комплект; Unix-скрипты используют `adb`
из PATH. Встраивание ADB для всех ОС относится к новому формату.

| Команда | Назначение |
| --- | --- |
| `./make_release.sh 3.13.0 --full-only` | Только Full |
| `./make_release.sh 3.13.0 --light-only` | Только Light |
| `./make_release.sh 3.13.0 --no-zip` | Собрать APK и папки, без новых ZIP |
| `./make_release.sh 3.13.0 --no-build` | Перепаковать с APK из существующих папок этой версии в `Releases/build/` |

`--no-build` не обновляет APK из изменённых исходников; для нового выпуска
используйте обычную команду. `--legacy VERSION` остаётся совместимым псевдонимом
старого режима.

## 5. Независимые payload и GUI

```sh
./make_release.sh 3.13.0 --payload
./Installer/scripts/build-all-macos.sh --mac
```

Первая команда собирает одну общую пару APK с metadata для Full/Light и проверяет весь
recipe/payload. Результаты: `Releases/dist/payload_3.13.0.zip`, `payload_3.13.0.json`,
`Releases/build/installer-payload-3.13.0/`. Desktop/Colima не запускаются.
Вторая команда собирает только GUI/ADB/recovery в `Releases/build/installers-1.0.0/`.
`--payload DIRECTORY` у desktop-сборки допускает дополнительный offline bundle.

Совместимая обёртка `./make_release.sh 3.13.0 --mac` делает обе операции и складывает
GUI ZIP в `Releases/dist/VoyahTune-Installer-1.0.0/`, с SHA256SUMS и release.json.
Без платформенных флагов `--installers` выбирает все три ОС. Версии в этих примерах
замените фактическими версиями автомобильного комплекта и Cargo соответственно.

Локальная повторная сборка атомарно заменяет прежние результаты после успеха.
В каталоге GUI ZIP остаются только выбранные платформы. Ошибка сборки сохраняет
предыдущие результаты. Опубликованные байты существующего релиза заменять нельзя.
`--no-zip` собирает только внутренний payload; `--no-build` допускает только APK
с точно совпадающими версией, revision, recipe и runtime metadata.

Классические Full/Light ZIP используют одни APK из одной Gradle-сборки. Их установка
тоже пишет режим и очищает ключ после удаления. `--full-only`
и `--light-only` относятся к классическому формату доставки.
Не запускайте две Android-сборки одного checkout одновременно.

## 6. Проверки и публикация

1. Выполните проверки [движка](installer-classic-port.md#как-проверять-изменения),
   `npm --prefix Installer/desktop run check` и build интерфейса.
2. Проверьте APK сертификаты и общий состав для Full/Light. Подписи должны совпадать
   с прежним выпуском для сохранения данных. Universal APK имеют `supportedModes`, без flavor.
3. Проверьте GUI без полного payload, каталог/кэш/локальный ZIP, требования обновления,
   отмену загрузки, подтверждение автомобиля и CAN consent. `build-info.json` содержит
   `installerVersion` и `embeddedPayload: false` для обычного GUI.
4. На согласованном тестовом ГУ отдельно проверьте Full/Light, Light → Full,
   soft cleanup Full → Light с сохранением данных, remove → Light, DNS, CE/DE и ранний запуск приложений/loader.
   Fake ADB и macOS не подтверждают Windows или поведение автомобиля.

Проверки готовых ресурсов выполняет сборочная утилита, не распространяемый CLI:

```sh
Installer/target/release/installer-build verify-payload Releases/build/installer-payload-3.13.0
Installer/target/release/installer-build verify-host '/path/VoyahTune Installer.app/Contents/Resources/bundle'
python3 Installer/scripts/update-catalog.py
```

Последняя команда только валидирует локальный index. Публикация двухфазная:
сначала загрузите проверенный ZIP в GitHub Release `v3.13.0` репозитория
`nexron171/VoyahTune` (remote `github.com`), затем выполните:

```sh
python3 Installer/scripts/update-catalog.py \
  --entry Releases/dist/payload_3.13.0.json --verify-remote
```

Генератор проверяет публичный HTTPS asset, размер/SHA-256 и формат всего index, затем
атомарно добавляет запись. Ошибка сети/хеша оставляет index прежним. Повтор идемпотентен;
замена существующей версии и её requirements отклоняется. Только после этого коммитьте
и публикуйте `Installer/releases/index.json` в ветке GitHub `master-od`. Если публикация
index не состоялась, загруженный ZIP остаётся доступным для локального импорта;
повторите публикацию index. Команды сборки сами не создают тег и не публикуют assets.

Первый переход: один раз установить новый GUI 1.0.0. Старые автономные GUI не умеют
читать каталог и не обновятся автоматически. Новый GUI принимает старую папку payload
через legacy adapter, новые ZIP — schema 3. После первого скачивания интернет для
установки не обязателен. Для будущей новой логики увеличьте версию GUI и requirements
payload; добавьте HTTPS-ссылки обновления по платформам в `installerDownloads` каталога.
Пока реальные GUI assets не опубликованы, таких ссылок в index нет.

Developer ID/notarization и Authenticode пока не настроены. Готовые локальные артефакты
не считаются опубликованным релизом или подтверждением испытаний на ГУ.
