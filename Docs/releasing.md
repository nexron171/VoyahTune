# Подготовка окружения и выпуск VoyahTune

Актуально на 4 октября 2026 года. Команды выполняются из корня репозитория.
`3.22.0` в примерах — условный **новый** релиз. Замените его своим ещё не
опубликованным номером. Версия установщика задаётся отдельно.

Сборка содержимого релиза обязательно выбирает ровно одну инфраструктуру: `--pi`
или `--od`. Исходники PI находятся в `Packaging/pi/`, OD — в `Packaging/od/`;
Java-база Native и RestoreMode общая. Выбор фиксируется в APK metadata, manifest
payload и updater. Версия релиза включает суффикс `-pi`/`-od`: из базовой `3.22.0`
сборщик получает `3.22.0-pi` или `3.22.0-od`. Противоречащий флагу суффикс запрещён.

GUI-установщик общий. Один каталог содержит оба релиза отдельными строками; пользователь
выбирает нужную версию. При установке GUI берёт инфраструктуру из проверенного payload,
а root-служба OTA показывает только свой профиль. Старые версии без суффикса считаются OD.
Новые примеры публикации ниже используют полный номер OD `3.22.0-od`.

```sh
./make_release.sh 3.22.0 --od --payload
./make_release.sh 3.22.0 --pi --payload
./Installer/scripts/build-all-macos.sh --mac
```

Первый выпуск нового контракта требует пересборки общего установщика: минимум 1.5.0,
capability `infrastructure-v1`. Последующие совместимые обновления только APK/hooks
не требуют нового GUI. Для смены PI/OD сначала удаляют VoyahTune, затем
пользователь выбирает и устанавливает другой релиз. В Android нет настройки
переключения инфраструктуры; специального сценария перехода в GUI нет.

Для публикации payload и установщиков в Yandex S3 вместо GitHub assets используйте
[процесс выпуска в S3](releasing-s3.md). Он включает автоматическую загрузку и
HEAD-проверку без повторного скачивания архивов; каталог остаётся в GitHub.
Payload размещается непосредственно в корневой папке версии `vVERSION/`,
а установщики — непосредственно в отдельной папке `Installers/INSTALLER_VERSION/`.
Версии VoyahTune и Installer независимы. Вложенность этих папок друг в друга
и дополнительные уровни вроде `Installers/INSTALLER_VERSION/builds/RELEASE_VERSION/`
запрещены для будущих публикаций; подробности — в
[правиле размещения артефактов](releasing-s3.md#обязательные-пути-артефактов).
Исправление установщика не меняет ранее опубликованный payload и запись OTA-каталога.

Обычный выпуск через GitHub Releases состоит из шести действий:

1. Подготовить исходники, версии APK, описание изменений и коммит.
2. Собрать `./make_release.sh 3.22.0 --od --payload`.
3. Проверить полученный релиз и установку в согласованном объёме.
4. Загрузить `payload_3.22.0-od.zip` в опубликованный GitHub Release `v3.22.0-od`.
5. Добавить сгенерированную запись в каталог с проверкой удалённого ZIP и опубликовать
   `Releases/ota/index.json` в ветке `master-od`.
6. В прежнем GUI нажать «Обновить список», скачать новый релиз и проверить его выбор.

**Если процесс установки не изменился, GUI пересобирать и распространять заново не нужно.**
Сборка создаёт локальные файлы; загрузка в GitHub Releases и публикация каталога —
отдельные действия. Ни одна команда сборки не выполняет их автоматически.

## 1. Выбрать формат релиза

| Формат | Команда | Результат |
| --- | --- | --- |
| По старому, со скриптами | `./make_release.sh 3.22.0 --od` | Единый ZIP с install/remove |
| Только единый payload | `./make_release.sh 3.22.0 --od --payload` | `payload_3.22.0-od.zip` и заготовка записи каталога |
| Payload и GUI macOS | `./make_release.sh 3.22.0 --od --mac` | Payload ZIP и самостоятельный Universal GUI ZIP |
| Только установщик macOS | `./Installer/scripts/build-all-macos.sh --mac` | Universal GUI без сборки Android |

Для содержимого релиза без флагов формата используется классический ZIP; `--pi`/`--od` обязателен.
Самостоятельная сборка общего GUI не принимает эти флаги. `--installers` собирает payload и GUI
для всех платформ; флаги `--mac`, `--windows`, `--linux` ограничивают платформы.
Windows ARM/Linux ARM не собираются. Windows x64/x86 выбираются через
`--windows-arch`; запуск на Windows проверяется отдельно от кросс-сборки.
Объём предыдущей проверки на ГУ указан
в [архитектуре установщика](installer-architecture.md); новый релиз требует своих проверок.

GUI пересобирается при изменении логики, встроенных ресурсов или поддерживаемого контракта. Обычный новый релиз — общий payload
и обновление каталога. ADB и небольшой remover входят в GUI, APK скачиваются/импортируются
из GUI. Установщик и VoyahTune имеют независимые версии. Пользовательского CLI нет.
Windows-пакет не включает WebView2: если runtime отсутствует, установщик скачивает
bootstrapper Microsoft, который устанавливает WebView2 через интернет. Если WebView2
уже установлен, повторная загрузка не требуется. Linux требует X11/XWayland и glibc Ubuntu 22.04.

Готовые архивы/бинарники хранятся в игнорируемом `Releases/`. В Git хранится spec и
[каталог](../Releases/ota/index.json); запись каталога генерируется по готовому payload.
[Контракты форматов](installer-protocol.md).

### Отдельный каталог OTA

Общий установщик и обе root-службы читают [Releases/ota/index.json](../Releases/ota/index.json).
Старый `Installer/releases/index.json` остаётся для прежних установщиков; новые записи
туда не добавляются. В новом каталоге у релиза только `version`, `url`, `size`, `sha256`.
Отдельных метаданных и подписи OTA нет. [Формат каталога](ota-release-format.md).
Требования проверяются по манифесту после скачивания, перед установкой.
Каталог содержит обе версии `VERSION-pi` и `VERSION-od`; GUI показывает обе,
OTA фильтрует свой суффикс до выбора последней версии и скачивания.
Профиль проверяется ещё раз по подписанным APK metadata и manifest.

Рядом хранится отдельный [beta-каталог](../Releases/ota/index-beta.json) с тем же
форматом. Добавлять в него релизы разрешено только по явной просьбе пользователя;
при beta-публикации основной каталог не изменяется. Ветки, сборки и суффикса
версии недостаточно для автоматического добавления. Для beta явно передавайте
`--index Releases/ota/index-beta.json`; перенос записей между каталогами также
требует отдельного поручения. [Команды beta-публикации](releasing-s3.md#beta-каталог).
Появление файла не переключает установленный GUI или root-службу на beta-URL.

Первый исторический релиз с root-службой требовал Installer 1.2.0 и `ota-bootstrap-v1`.
Изменение URL/формата каталога и удаления ключа требует пересборки root-службы,
payload и установщиков со встроенным remover. Прежние сборки с подписанным каталогом
этот формат не читают; одной замены URL недостаточно. Перед сетевой приёмкой
нужна USB-установка пересобранной службы. [Приёмка](ota-acceptance.md).
Для новых PI/OD payload минимум Installer — 1.5.0 из-за проверки инфраструктуры.
Новый номер VoyahTune или адрес каталога сами по себе минимум не повышают.

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
- Node.js 22 с npm и Python 3.12+: нужны также классическому формату для проверок;
- Go 1.23+ для сборки PI loaderFrida; OD Go не требует;
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

sdkmanager "platforms;android-35" "platforms;android-36" "build-tools;35.0.0" \
  "ndk;27.0.12077973" "cmake;3.22.1" "platform-tools"
sdkmanager --licenses
```

Android API 36 нужен дополнительному PI-приложению RunYN; Native/RestoreMode используют API 35.
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
необходимый входной файл проекта. Rust, Tauri и Docker для классического формата не нужны. PI loader собирается
скриптом `Packaging/pi/loaderFrida/build.sh`; можно указать путь к Go через `GO_BIN`.

### 2.3. Дополнительно для payload и GUI macOS

Установите Python 3.12+ и Rust через rustup. Версия Rust закреплена в
`Installer/rust-toolchain.toml`, сейчас это `1.98.1`:

```sh
rustup toolchain install 1.98.1 --profile minimal --component rustfmt,clippy
rustup target add --toolchain 1.98.1 aarch64-apple-darwin x86_64-apple-darwin
python3 --version
npm --version
./Installer/scripts/build-all-macos.sh --check --mac
```

Для `--payload` также нужны Python и Rust: Python запускает сборку, Rust-утилита
формирует и проверяет payload. Desktop и контейнеры в этом режиме не собираются;
два Apple target и проверка `--check --mac` нужны при сборке самого GUI.

Desktop-зависимости устанавливаются автоматически через `npm ci` во время сборки.
Глобально устанавливать Tauri CLI не требуется.

Теперь можно выполнять `./make_release.sh 3.22.0 --od --mac`. При выборе только macOS
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

### Исходники и файлы релиза

| Что меняется | Где редактировать |
| --- | --- |
| Native / RestoreMode; дополнительный PI RunYN | `Native/app/src/`, `RestoreMode/app/src/`, `RunYN/app/src/` |
| Hooks и конфигурации | `Packaging/pi/inject/` или `Packaging/od/inject/` |
| Loader, boot RC/SH | `Packaging/pi/system/` или `Packaging/od/system/`; PI loader — `Packaging/pi/loaderFrida/` |
| Общий permission whitelist и updater RC | `Packaging/system/` |
| Frida и готовые инструменты | `Packaging/pi/tools/` или `Packaging/od/tools/` |
| DNS helper / готовый overlay APK | `Packaging/pi/installer/common/` или `Packaging/od/installer/common/`, `Packaging/vendor-overlay/` |
| Классические установка/удаление | `Packaging/pi/installer/device/` или `Packaging/od/installer/device/` |
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
В общем `Packaging/installer/payload-spec.json` укажите особые режимы/пути/права и добавьте
устаревшие пути в накопительный `removeFiles`. Классические `.sh/.bat` обновляются отдельно.
Новая системная роль/операция требует изменения core, capability и версии GUI.
[Карта сохранённых специальных процедур](installer-classic-port.md).

`manifest.json` и другие JSON внутри сборочного payload создаются автоматически:
их не правят вручную. Не редактируйте `Releases/` вместо исходников.

### Версии и подписи

1. Выберите SemVer-версию релиза, например `3.22.0`. Она передаётся аргументом
   `make_release.sh`; `@VERSION@` в исходных скриптах заменяется автоматически.
2. Проверьте версии APK: Native, RestoreMode и PI RunYN получают `versionName`
   из `voyahReleaseVersion`, а `versionCode` — из базового `major.minor.patch`.
   Новый базовый номер должен повышать `versionCode`; `-pi`/`-od` его не изменяет.
3. Только если новый релиз несовместим с прежним процессом установки и требует
   новой логики, увеличьте `workspace.package.version` в
   `Installer/Cargo.toml`, `version` в `Installer/desktop/src-tauri/tauri.conf.json`
   и записи локальных пакетов в `Installer/Cargo.lock`.
   Это единая версия GUI/движка и desktop-пакетов, независимая от payload.
   Совместимые изменения интерфейса, упаковки и добавление архитектуры не повышают
   этот номер; пересоберите только затронутые платформы.
4. Обновите описание изменений. Проверьте diff и сохраните готовые исходники
   в коммите перед распространяемой сборкой.

Новый формат записывает версию релиза и Git revision в подписанные метаданные
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
./make_release.sh 3.22.0 --od
```

Скрипт запускает проверки Packaging, собирает единые APK обоих приложений,
для PI также собирает RunYN, раскладывает файлы и создаёт:

```text
Releases/dist/VoyahTune-3.22.0-od.zip
Releases/build/VoyahTune-3.22.0-od/
```

В каждом ZIP — плоская папка с APK, ресурсами, `install.sh/.bat`, `remove.sh/.bat`
и README. Windows ADB входит в старый релиз; Unix-скрипты используют `adb`
из PATH. Встраивание ADB для всех ОС относится к новому формату.

| Команда | Назначение |
| --- | --- |
| `./make_release.sh 3.22.0 --od --no-zip` | Собрать APK и папки, без новых ZIP |
| `./make_release.sh 3.22.0 --od --no-build` | Перепаковать с APK из существующих папок этой версии в `Releases/build/` |

`--no-build` не обновляет APK из изменённых исходников; для нового выпуска
используйте обычную команду. `--legacy VERSION` остаётся совместимым псевдонимом
старого режима.

## 5. Независимые payload и GUI

```sh
./make_release.sh 3.22.0 --od --payload
./Installer/scripts/build-all-macos.sh --mac
```

Первая команда собирает Native/RestoreMode с metadata релиза (PI также содержит RunYN) и проверяет весь
recipe/payload. Результаты: `Releases/dist/payload_3.22.0-od.zip`, `payload_3.22.0-od.json`,
`Releases/build/installer-payload-3.22.0-od/`. Desktop/Colima не запускаются.
Вторая команда собирает только GUI/ADB/recovery в `Releases/build/installers-1.5.0/`.
`--payload DIRECTORY` у desktop-сборки допускает дополнительный offline bundle.

Совместимая обёртка `./make_release.sh 3.22.0 --od --mac` делает обе операции и складывает
GUI ZIP в `Releases/dist/VoyahTune-Installer-1.5.0/`, с SHA256SUMS и release.json.
Без платформенных флагов `--installers` выбирает все три ОС. Версии в этих примерах
замените фактическими версиями автомобильного релиза и Cargo соответственно.

Локальная повторная сборка атомарно заменяет прежние результаты после успеха.
В каталоге GUI ZIP остаются только выбранные платформы. Ошибка сборки сохраняет
предыдущие результаты. Опубликованные байты существующего релиза заменять нельзя.
`--no-zip` собирает только внутренний payload; `--no-build` допускает только APK
с точно совпадающими infrastructure, версией, revision, recipe и runtime metadata.

Классический ZIP содержит Native/RestoreMode и runtime выбранной инфраструктуры,
а PI также содержит RunYN.
`--pi`/`--od` задаёт содержимое сборки; выбора инфраструктуры во время установки нет.
Не запускайте две Android-сборки одного checkout одновременно.

## 6. Выпустить новый payload и обновить каталог

### 6.1. Собрать и проверить локальный результат

После подготовки исходников, версий и коммита из раздела 3 выполните:

```sh
./make_release.sh 3.22.0 --od --payload
Installer/target/release/installer-build verify-payload Releases/build/installer-payload-3.22.0-od
python3 -m json.tool Releases/dist/payload_3.22.0-od.json
```

Здесь и ниже указан стандартный `Installer/target`. Если используете собственный
`CARGO_TARGET_DIR`, скорректируйте путь к `installer-build`; скрипт каталога принимает
`--builder /абсолютный/путь/installer-build`.

| Файл | Назначение | Куда публиковать |
| --- | --- | --- |
| `Releases/dist/payload_3.22.0-od.zip` | Архив релиза | Asset GitHub Release `v3.22.0-od` |
| `Releases/dist/payload_3.22.0-od.json` | Сгенерированная запись: version/url/size/sha256 | Передать в `update-catalog.py`; GUI этот отдельный файл не читает |
| `Releases/build/installer-payload-3.22.0-od/` | Распакованный payload для проверки и локального импорта | Публиковать папку не требуется |
| `Releases/ota/index.json` | Основной каталог доступных версий | В GitHub-ветку `master-od` по этому точному пути |
| `Releases/ota/index-beta.json` | Отдельный beta-каталог, пополняемый по явному поручению | Публикация только в согласованном объёме, основной каталог не меняется |

В корне ZIP должен находиться `manifest.json`, а не дополнительная папка-обёртка.
Не перепаковывайте ZIP после генерации JSON: размер и SHA-256 относятся к точным байтам
архива. `manifest.json`, recipe и подписанные metadata APK генерируются сборщиком.

Перед публикацией:

- Выполните проверки затронутых компонентов: Android JVM-тесты для изменений APK;
  тесты соответствующих hooks/loader для Packaging; Rust и GUI check/build для движка
  и интерфейса. Проверки Packaging, вызванные сборкой, не заменяют всю нужную матрицу.
- Сравните сертификаты обоих APK с предыдущим выпуском. Другой ключ вызывает
  переустановку с потерей данных; выпуск с сохранением настроек требует прежнего ключа.
- Проверьте `infrastructure`, `releaseVersion`, `buildRevision`, состав APK и `requirements`
  в распакованном manifest; запись каталога содержит только version/url/size/sha256. Для распространяемой сборки
  используйте зафиксированный коммит без суффикса `-dirty`.
- Откройте ZIP в уже выпущенном совместимом GUI. При согласованной проверке на ГУ
  проверьте установку, обновление поверх в рамках той же инфраструктуры, настройки, DNS
  и запуск после перезагрузки. Для смены PI/OD сначала удалите VoyahTune.
  Полное удаление и смена
  подписи — отдельные сценарии с потерей данных, их не смешивают с проверкой сохранности.

Команды основных проверок из корня репозитория; выбирайте по изменённым компонентам:

```sh
(cd Native && ./gradlew testDebugUnitTest)
(cd RestoreMode && ./gradlew testDebugUnitTest)
cargo test --manifest-path Installer/Cargo.toml
cargo test --manifest-path Installer/desktop/src-tauri/Cargo.toml
npm --prefix Installer/desktop run check
npm --prefix Installer/desktop run build
python3 Installer/scripts/sync-classic-commands.py --check
```

Список профильных сценариев Packaging и fake ADB приведён в
[карте проверок установщика](installer-classic-port.md#как-проверять-изменения).
`installer-build` — внутренний инструмент разработчика, в пользовательский GUI он не входит.

### 6.2. Опубликовать ZIP в GitHub Releases

В репозитории `nexron171/VoyahTune` создайте выпуск через GitHub Releases:

1. Убедитесь, что коммит исходников, по которому собран payload, уже доступен в GitHub.
2. Создайте или выберите тег **`v3.22.0-od`**, указывающий именно на этот коммит.
   Не привязывайте тег к случайному текущему HEAD другой ветки.
3. Заполните название и описание изменений, укажите требования и реально проверенные
   платформы/прошивки. Черновик можно использовать для подготовки.
4. Прикрепите **`Releases/dist/payload_3.22.0-od.zip`** как asset с именем
   **`payload_3.22.0-od.zip`** и опубликуйте выпуск. На следующем шаге ZIP должен быть
   публично доступен без авторизации; asset в draft для этого не подходит.

Ожидаемый адрес, который сборщик уже записал в JSON:

```text
https://github.com/nexron171/VoyahTune/releases/download/v3.22.0-od/payload_3.22.0-od.zip
```

Автоматические архивы GitHub «Source code» не являются payload.
Если выбрали другой тег, репозиторий или имя asset, до обновления каталога исправьте
`url` в сгенерированном `payload_3.22.0-od.json`.
Не меняйте размер и SHA-256, чтобы замаскировать несовпадение сборки.

### 6.3. Добавить релиз в локальный каталог

Начните с актуального `Releases/ota/index.json` из `master-od`, сохранив
имеющиеся записи. Затем выполните из корня checkout:

```sh
python3 Installer/scripts/update-catalog.py \
  --entry Releases/dist/payload_3.22.0-od.json \
  --verify-remote
python3 Installer/scripts/update-catalog.py
git diff -- Releases/ota/index.json
git diff --check
```

Первая команда валидирует объединённый каталог, полностью скачивает опубликованный
ZIP по HTTPS, сверяет размер и SHA-256 и только после успеха атомарно обновляет index.
Проверка использует публичный URL, а не авторизованную сессию браузера. При ошибке
сети или хеша index остаётся прежним. Вторая команда только валидирует локальный index;
она ничего не публикует и не проверяет все удалённые архивы заново.

В diff должна появиться новая запись из четырёх полей; существующие версии сохраняются.
Повтор с теми же параметрами не создаёт дубликат. URL можно заменить зеркалом при
неизменных размере/SHA-256; другие байты требуют нового номера релиза.
Удалённая проверка при вызове с `--entry` выполняется даже для существующей записи.

### 6.4. Опубликовать каталог в правильную ветку

Сохраните изменение `Releases/ota/index.json` отдельным коммитом и доставьте
его в **`master-od` репозитория `nexron171/VoyahTune`** обычным для проекта способом:
через merge/PR или публикацию подготовленного коммита. Если работаете в
`installer-remake` или другой ветке, один push этой ветки не обновляет каталог для
пользователей. Перед объединением подтяните актуальный index, чтобы не потерять
параллельно опубликованные релизы.

GUI читает единственный фиксированный адрес:

```text
https://raw.githubusercontent.com/nexron171/VoyahTune/master-od/Releases/ota/index.json
```

После публикации скачайте именно публичный index и проверьте, что новая версия в нём есть:

```sh
curl --fail --location \
  'https://raw.githubusercontent.com/nexron171/VoyahTune/master-od/Releases/ota/index.json' \
  --output Releases/dist/published-index.json
Installer/target/release/installer-build verify-catalog Releases/dist/published-index.json
python3 - <<'PY_CHECK'
import json
from pathlib import Path
index = json.loads(Path('Releases/dist/published-index.json').read_text())
assert any(r['version'] == '3.22.0-od' for r in index['releases']), 'Новый релиз ещё не опубликован в каталоге'
print('Релиз 3.22.0-od присутствует в публичном каталоге')
PY_CHECK
```

Размещение index только среди assets GitHub Release или по другому пути/в другой ветке
не меняет этот адрес. Локальный файл без commit/push также не виден пользователям.

### 6.5. Проверить выпуск в прежнем GUI

1. Запустите уже выпущенный установщик или нажмите **«Обновить список»**.
2. Убедитесь, что в «Доступных релизах» появилась `3.22.0-od`, а ссылка изменений ведёт
   на нужный GitHub Release.
3. Нажмите **«Выбрать»** в строке `3.22.0-od`. GUI проверит ZIP, распакует его и предложит
   релиз. Сам выбор и скачивание ещё не запускают установку.
4. Для проверки именно сетевого скачивания используйте GUI-профиль/компьютер,
   где этого ZIP ещё нет в кэше. Мгновенный выбор уже скачанного релиза подтверждает
   работу кэша, но не загрузку asset из GitHub.
5. После скачивания проверьте повторный выбор строки со статусом «Скачан» без сети.
   Установка на автомобиль выполняется отдельно в согласованном объёме.

Суффиксы `-pi` и `-od` обозначают поддерживаемую инфраструктуру релиза и видны
в обычном списке. Прочие предварительные версии, например `3.22.0-rc.1`,
не появляются в списке стабильных релизов.
Флажок prerelease на странице GitHub не управляет этим выбором. Для предварительных проверок
используйте локальный импорт ZIP.

## 7. Если требуется новая версия установщика

Изменение APK, hooks, конфигурации или состава файлов в пределах существующего recipe
обычно требует только нового payload. Пересобирайте GUI при изменении его интерфейса,
движка, специальной процедуры установки/удаления, встроенного ADB/recovery или
поддерживаемого протокола. Исправление GUI само по себе не требует выпуска нового payload.

Если новый payload требует новой логики:

1. Увеличьте версию установщика в `Installer/Cargo.toml` и соответствующие записи
   `Installer/Cargo.lock`. Release-конфигурация Tauri получает версию из Cargo при сборке.
2. Задайте минимально необходимую версию в `Requirements::infrastructure()` файла
   [compatibility.rs](../Installer/crates/release-core/src/compatibility.rs).
   При новой семантике добавьте capability в `CAPABILITIES` и реализуйте её поддержку.
   Сейчас сборщик берёт requirements из этого кода; отдельного аргумента командной
   строки или поля `payload-spec.json` для minInstallerVersion нет.
3. Соберите и проверьте GUI, затем соберите требующий его payload. Поле `requirements`
   находится внутри ZIP; в простом каталоге требований нет.
   Не повышайте минимум для payload, который по-прежнему поддерживается старым GUI.
4. Сначала опубликуйте проверенный GUI для поддерживаемых платформ. Затем опубликуйте
   payload и каталог по разделу 6. Совместимость нового клиента проверяется по манифесту после скачивания.

Только macOS GUI, без пересборки Android:

```sh
./Installer/scripts/build-all-macos.sh --mac
```

Результат — `Releases/build/installers-<версия-GUI>/macos-universal.tar.gz` и
`build-info.json`. Внутри архива самостоятельный `.app`. Чтобы получить привычный ZIP
для публикации, запакуйте готовый `.app` на macOS, сохранив его структуру и права:

```sh
INSTALLER_VERSION=1.5.0  # замените фактической версией из Cargo.toml
mkdir -p Releases/dist
ditto -c -k --keepParent \
  'Installer/target/universal-apple-darwin/release/bundle/macos/VoyahTune Installer.app' \
  "Releases/dist/VoyahTune-Installer-${INSTALLER_VERSION}-macos.zip"
```

Если нужен и новый payload, и GUI за один запуск, используйте
`./make_release.sh 3.22.0 --od --mac`: готовые GUI ZIP будут в
`Releases/dist/VoyahTune-Installer-<версия-GUI>/`. Флаг `--installers` без ограничения
платформ запускает также Windows/Linux; для одной macOS используйте `--mac`.

Ссылки на новые установщики публикуйте в описании GitHub Release и инструкции установки.
В `Releases/ota/index.json` нет списка установщиков; GUI не обновляет собственный
исполняемый файл. В каталог добавляется только запись payload.

Первый переход со старого автономного установщика на GUI с каталогом требует одной
ручной установки нового GUI. Developer ID/notarization и Authenticode пока не настроены.

## 8. Если релиз не появился или публикация прервалась

| Симптом | Что проверить / сделать |
| --- | --- |
| ZIP есть в Release, версии нет в GUI | Добавлена ли запись в публичный index ветки `master-od`; нажато ли «Обновить список» |
| Каталог отвечает 404 | Репозиторий, ветка и путь из фиксированного URL; локальный index недостаточен |
| Asset отвечает 404 | Выпуск опубликован, не draft; точные тег `vVERSION` и имя `payload_VERSION.zip` (VERSION уже включает `-od`/`-pi`); публичный доступ |
| Версия есть в JSON, но скрыта | Проверить SemVer: версии с `-rc`/`-beta` не показываются в обычном списке |
| GUI требует обновление | Сверить minInstallerVersion/capabilities; опубликовать подходящий GUI и ссылку, не занижать требования |
| Ошибка размера/SHA-256 | Загружен тот же ZIP, из которого создан entry; архив не переименован с заменой содержимого и не перепакован |
| После обновления показывается старый список | Проверить публичный raw index; при сетевой ошибке GUI использует прежний кэш и показывает предупреждение |
| `Published version is immutable` | Эту версию уже публиковали с другими байтами; выпускать исправление под новым номером |
| ZIP опубликован, commit index не доставлен | Повторить проверку entry и публикацию index; пересборка ZIP не нужна |

Если опубликован ошибочный релиз, сначала уберите его запись из актуального index,
провалидируйте и опубликуйте каталог. Затем выпустите исправление
под новым номером. Не подменяйте байты уже объявленного payload. Удаление записи из
каталога не удаляет скачанный кэш у пользователей и не откатывает установленный релиз;
автоматический downgrade не предусмотрен.

## 9. Чеклист выпуска

- [ ] Выбрана инфраструктура `pi`/`od`, новый номер релиза, версии APK и прежние ключи подписи.
- [ ] APK metadata, manifest и updater соответствуют суффиксу версии; общий GUI/recovery поддерживает оба профиля.
- [ ] Исходники и описание изменений зафиксированы; сборка соответствует выбранному коммиту.
- [ ] Единый payload собран, manifest/recipe/requirements и сертификаты проверены.
- [ ] Проверки затронутых компонентов и согласованные испытания выполнены, ограничения записаны.
- [ ] ZIP загружен в опубликованный GitHub Release; тег соответствует коммиту сборки.
- [ ] `update-catalog.py --entry ... --verify-remote` успешно проверил опубликованные байты.
- [ ] Каталог опубликован в `master-od`; новая версия найдена в публичном raw index.
- [ ] Прежний совместимый GUI увидел релиз, скачал и выбрал его; отдельно проверен кэш.
- [ ] Если понадобился новый GUI: опубликованы проверенные пакеты и ссылки в описании выпуска.

## Контракт Installer 1.5.0

Новые сборки используют payload schema 4, recipe schema 3 / `qinggan-v3`, APK metadata
schema 3 и поле `infrastructure: "pi" | "od"`. Сборщик сверяет профиль обоих APK,
loader и runtime. Общий GUI выбирает процесс по payload; updater отклоняет
чужой профиль до применения. Старые
metadata без поля трактуются Rust-проверкой как OD, но новые сборки всегда его записывают.
Минимум нового payload — 1.5.0; capabilities — `qinggan-v3`, `single-package-v1`,
`files-v1`, `ota-bootstrap-v1`, `infrastructure-v1`. Нужны пересобранные общий GUI/remover и updater выбранной инфраструктуры. Не переписывайте опубликованные
архивы и записи: выпускайте новый релиз. Сборка и тесты не означают публикацию
или подтверждённую работу на автомобиле.
