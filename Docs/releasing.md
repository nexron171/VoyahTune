# Подготовка окружения и выпуск VoyahTune

Все новые сборки VoyahTune единые. Один `payload_VERSION.zip` используется
компьютерным установщиком и OTA. Runtime берётся из `Packaging/new/`; метаданные APK
содержат `infrastructure: unified` и `payloadFormat: 5`. Особенности автомобилей учитываются адаптерами
и условиями по модели; отдельный вариант релиза выбирать не нужно.

Команды выполняются из корня репозитория. `4.5.0` ниже — условный новый номер,
а не указание изменить текущую версию. Источник версий — [versions.json](../versions.json):
`voyahtune` для stable payload и APK, `installer` для GUI/движка. Beta получает
согласованный номер с суффиксом `-beta`, например `4.5.0-beta`.

Текущий единый контракт требует Installer **1.9.0**, capability `fixed-payload-v1`
и совместимую root-службу. Первый переход на неё выполняется через USB; следующие
совместимые обновления используют тот же payload через OTA. Общий recipe заменяет
прежний runtime, удаляет известные устаревшие файлы и перезагружает Android.
RunYN входит в архив и устанавливается по условию `except-h97xy`.
[Состав и ограничения](installer-payload.md) · [Протокол](installer-protocol.md).

Сборка, установка на автомобиль, публикация, commit и push — отдельные действия,
которые выполняются в согласованном объёме. Документация не разрешает их автоматически.

## 1. Выбрать состав сборки

| Что собрать | Команда |
| --- | --- |
| Только единый payload | `./make_release.sh 4.5.0 --payload --minimum-installer-version 1.9.0 --minimum-ota-version 4.5.0` |
| Payload и GUI macOS | `./make_release.sh 4.5.0 --mac --minimum-installer-version 1.9.0 --minimum-ota-version 4.5.0` |
| Payload и стандартный набор GUI | `./make_release.sh 4.5.0 --installers --minimum-installer-version 1.9.0 --minimum-ota-version 4.5.0` |
| Только GUI macOS | `./Installer/scripts/build-all-macos.sh --mac` |

`--pi`, `--od`, `--legacy` и выбор инфраструктуры в Rust-сборщике удалены.
`--minimum-installer-version` и `--minimum-ota-version` обязательны.
Оба значения имеют формат `major.minor.patch`. Минимум Installer передаётся в APK
и переносится из него в JSON релиза; при `--no-build` он должен совпадать с минимумом готового APK. В примерах минимум совпадает с номером первого
релиза с новой службой. Для последующих совместимых релизов сохраняйте прежний
подтверждённый минимум; не подставляйте новый номер автоматически.

`--installers` выбирает macOS Universal, Windows x86/x64/ARM64 и Linux amd64.
Без `--installers` флаги `--mac`, `--windows`, `--linux` выбирают
платформы; `--windows-arch x64|x86|arm64` ограничивает Windows одной архитектурой.
`--payload` нельзя совмещать с платформенными флагами. Запуск Windows GUI проверяется
отдельно от кросс-сборки. Linux требует X11/XWayland и glibc Ubuntu 22.04.

Payload и GUI имеют независимые версии. Изменения APK, агентов и конфигурации в рамках
поддерживаемого recipe обычно требуют только нового payload. GUI пересобирается при
изменении его поведения, встроенных ресурсов или поддерживаемого контракта.
ADB и небольшой remover входят в GUI; payload скачивается или импортируется отдельно.
`--offline-bundle` добавляет выбранный payload в установщик.

## 2. Один раз подготовить окружение

### 2.1. Выбрать сборочную машину

Основной путь полного выпуска — **macOS**. Оркестратор релиза
`make_release.sh --installers` сейчас требует macOS: Windows/Linux он собирает
в подготовленных Linux-контейнерах Colima.

На Windows/Linux можно нативно пересобрать установщик своей ОС без Android-сборки:
см. [Installer/BUILDING.md](../Installer/BUILDING.md). Это отдельная сборка desktop,
а не запуск полного macOS-оркестратора.

### 2.2. Среда для Android APK и единого runtime

Установите:

- Git, POSIX shell и Bash, `zip`, `unzip`, стандартные Unix-утилиты;
- Python 3.12+, host C++20 compiler и Rust из `Installer/rust-toolchain.toml` для контрактов runtime;
- Node.js 22 с npm для desktop GUI;
- Go 1.23+ для единого загрузчика `Packaging/new/`;
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

Android API 36 нужен приложению RunYN; Native/RestoreMode используют API 35.
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
необходимый входной файл проекта. Runtime собирается через `Packaging/new/build.sh`;
для него нужен Android NDK с `aarch64-linux-android30-clang`.

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

Теперь можно выполнять `./make_release.sh 4.5.0 --mac --minimum-installer-version 1.9.0 --minimum-ota-version 4.5.0`. При выборе только macOS
Docker/Colima не проверяются и не запускаются.

### 2.4. Дополнительно для Windows/Linux из macOS

Установите Docker CLI, Colima и `rsync`. Один раз подготовьте:

1. Colima profile `v` с `COLIMA_HOME=<repo>/Releases/cache/colima`;
2. постоянный контейнер `vti-windows` с cargo-xwin и Windows targets x64, x86 и ARM64;
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
Для обновления с сохранением данных продолжайте
использовать один ключ. Потерянный ключ нельзя восстановить из APK.

`local.properties` настройте заново. SDK, npm/Gradle/Rust-кэши и контейнеры можно
восстановить; `Releases/cache/` можно перенести для ускорения. При смене пути
checkout контейнеры нужно пересоздать с правильным mount `/work`.
Ключи и готовые сборки в Git не добавляйте.

## 3. Подготовить содержимое релиза

| Что меняется | Где редактировать |
| --- | --- |
| Приложения | `Native/`, `RestoreMode/`, `RunYN/`, `Updater/ui/` |
| Агенты, загрузчик, конфигурации и boot | `Packaging/new/` |
| Устаревшие runtime-пути для очистки | `Packaging/new/config/retired-files.txt` и общий Rust-план |
| Общие роли payload | `Installer/crates/release-core/src/recipe.rs`, `Packaging/system/` |
| Root-служба OTA | `Updater/daemon/` |
| Процесс установки и удаления | `Installer/crates/installer-core/` |
| Payload, recipe и совместимость | `Installer/crates/release-core/` |
| GUI | `Installer/desktop/` |
| Branding и DNS overlay | `Packaging/branding/`, `Packaging/vendor-overlay/` |
| Инструкции и описание изменений | `Docs/`, `hownews.md`, `readme.md` |

Сборщик запускает сборку `Packaging/new/` и получает фиксированные роли из
типизированного плана `release-core`. Идентификаторы и хеши runtime встраиваются
в Go-загрузчик после сборки зависимых компонентов. Новую runtime-роль добавляют
в исходники сборщика и скомпилированный план; изменение формата требует обновления
Installer/root-службы. Внешние agents/runtime/payload-манифесты не формируются.
Точные пути старых файлов сохраняются для очистки установок, пропускавших версии.

Версии Native/RestoreMode, ZIP и записи каталога должны совпадать.
RunYN имеет самостоятельную версию приложения; его включение определяется общим
контрактом, а установка — моделью. Runtime-хеши и digest recipe записываются
в подписанные APK metadata. Старые APK нельзя перепаковать с новыми агентами
и считать проверенным новым релизом.

При подготовке stable синхронизируйте производные версии из `versions.json`.
Для beta сохраняйте явно согласованный полный номер и не меняйте базовую stable-версию.
Не повышайте Installer из-за одного изменения root-службы или нового номера payload:
сначала проверьте совместимость существующего движка. Если изменился сам GUI,
встроенные ресурсы, упаковка или контракт, обновите его версию по правилам `AGENTS.md`.

Сборка записывает Git revision; изменённый checkout получает `-dirty`.
Для распространяемой сборки используйте согласованное зафиксированное состояние.
Коммит создаётся только по явному поручению.

Проверка сохранения сертификатов APK:

```sh
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs /path/to/previous.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs /path/to/new.apk
```

Сравните SHA-256 сертификата отдельно для Native и RestoreMode. Native/RestoreMode
APK на автомобиле не резервируются; для сохранения данных нужен прежний ключ подписи.

## 4. Собрать и проверить payload

```sh
./make_release.sh 4.5.0 --payload --minimum-installer-version 1.9.0 --minimum-ota-version 4.5.0
Installer/target/release/installer-build verify-payload Releases/build/installer-payload-4.5.0
python3 -m json.tool Releases/dist/payload_4.5.0.json
```

При собственном `CARGO_TARGET_DIR` скорректируйте путь к `installer-build`.
Результат:

| Файл | Назначение |
| --- | --- |
| `Releases/dist/payload_4.5.0.zip` | Один архив для USB и OTA |
| `Releases/dist/payload_4.5.0.json` | Запись каталога с хешем, размером и минимумами |
| `Releases/build/installer-payload-4.5.0/` | Распакованный payload для проверки |

В корне ZIP находится каталог `common/`; версия и формат встроены в APK. После генерации записи каталога ZIP нельзя
переупаковывать: его размер и SHA-256 относятся к точным байтам архива.
Локальная успешная сборка атомарно заменяет прежний результат; при ошибке сохраняется
предыдущий. Опубликованный stable-архив под тем же номером не заменяется.

`--no-zip` оставляет проверенный внутренний payload. `--no-build` использует только
APK с совпадающими версией, revision, recipe и runtime metadata; этот флаг не превращает
устаревшие APK в новый релиз. Не запускайте две Android-сборки checkout одновременно.

Проверьте `infrastructure: unified`, `releaseVersion`, состав, подписи, требования
и условие RunYN в готовом payload. Объём тестов выбирайте по изменениям:

```sh
(cd Native && ./gradlew testDebugUnitTest)
(cd RestoreMode && ./gradlew testDebugUnitTest)
sh Packaging/new/check.sh
cargo test --manifest-path Installer/Cargo.toml
npm --prefix Installer/desktop run check
npm --prefix Installer/desktop run build
```

Это перечень подходящих проверок, а не обязательный полный прогон для каждой правки.
Документация не требует сборки. Проверки fake ADB/эмулятора не подтверждают работу
конкретных OEM-приложений и автомобиля. При согласованной приёмке проверьте обновление
поверх, сохранность настроек, DNS, запуск после reboot и `voyahtune-loader --check-hooks`.
Смена подписи и удаление с данными — отдельные сценарии.

## 5. Минимумы и каталоги

`minimumInstallerVersion` в метаданных APK и `minimumInstallerVersion`
в записи каталога должны отражать один минимальный поддерживаемый GUI.
Для текущего выпуска выбран минимум Installer 1.9.0. Задавайте его через
`--minimum-installer-version`; последующее повышение версии Installer само по себе не меняет минимум.

`minimumOtaVersion` — базовая версия установленного VoyahTune с совместимой root-службой.
Если служба обновляется через USB, используйте номер впервые содержащего её релиза.
Если служба совместима, переносите минимум предыдущего опубликованного единого релиза
в выбранном каталоге, определяя его по SemVer. Всегда передавайте минимум явно.

Текущие GUI и root-служба получают основной каталог через
`https://voyahtune.ru/api/v1/releases`; пользовательский HTTPS-адрес можно задать отдельно.
Локальные JSON-индексы больше не ведутся. Beta выпускается только по отдельному
поручению. [Формат и правила выбора](ota-release-format.md).

## 6. Артефакты и серверный каталог

Результат сборки — проверенный ZIP и сопутствующий JSON с версией, размером,
SHA-256 и минимумами совместимости. Шаблонный URL из сборщика не подтверждает
публикацию: клиент должен получить фактический адрес архива из серверного каталога.

Сборка сама по себе не публикует файлы и не регистрирует релиз на сервере.
Старая инструкция загрузки в S3 и ручное обновление JSON-индексов отменены.
Актуальная процедура регистрации релиза не описана в этом репозитории;
её нельзя заменять прежними командами или придуманным запросом к API.

После согласованной публикации проверьте запись в фактически выбранном каталоге,
затем выбор и скачивание в GUI. Кэшированный ZIP не доказывает доступность сетевой
загрузки. Для обновления существующей beta сохраняйте её полный номер и обновляйте
размер/SHA-256 согласно новым байтам. Изменённому опубликованному stable-архиву
нужен новый номер.

## 7. Если требуется новый установщик

Версию задавайте в `versions.json.installer`, затем синхронизируйте Cargo/Tauri
и производные metadata согласно [Installer/BUILDING.md](../Installer/BUILDING.md).
Требования единого payload определяет `Requirements::unified()` в
[compatibility.rs](../Installer/crates/release-core/src/compatibility.rs).
Новая операция или семантика требует поддержки в исполнителях, capability и
минимальной версии, а не только изменения JSON.

```sh
./Installer/scripts/build-all-macos.sh --mac
```

Сборка GUI не собирает Android. Если нужны оба результата за один запуск:

```sh
./make_release.sh 4.5.0 --mac --minimum-installer-version 1.9.0 --minimum-ota-version 4.5.0
```

Developer ID/notarization и Authenticode проверяются отдельно от успешной сборки.
Установщики не перечисляются в payload-каталоге и не заменяют свою установленную
копию автоматически. Исправление GUI не меняет уже опубликованные байты payload.

## 8. Если релиз не появился

| Симптом | Что проверить |
| --- | --- |
| Архив опубликован, версии нет в GUI | Запись опубликована в фактически выбранном каталоге; список обновлён |
| Каталог или архив отвечает 404 | Точный публичный URL и доступность без локальной авторизации |
| Релиз требует USB | `minimumOtaVersion`, версия и возможности установленной root-службы |
| Ошибка размера или SHA-256 | Запись соответствует исходному ZIP; архив не переупакован после подсчёта |
| GUI требует обновления | Совместимость схемы, capability и минимум Installer |

Сохраняйте журнал сборки/публикации и различайте успешную локальную проверку,
доступность объекта по сети и подтверждённую установку на автомобиле.
