# Подготовка окружения и выпуск VoyahTune

Актуально на 9 сентября 2026 года. Команды выполняются из корня репозитория.
`3.10.0` в примерах замените номером своего релиза.

## 1. Выбрать формат релиза

| Формат | Команда | Результат |
| --- | --- | --- |
| По старому, со скриптами | `./make_release.sh 3.10.0` | Отдельные Full и Light ZIP с install/remove |
| По новому, с GUI и CLI | `./make_release.sh 3.10.0 --installers` | Три ZIP: macOS Universal, Windows x64, Linux x64 |
| Только GUI для macOS | `./make_release.sh 3.10.0 --mac` | Один ZIP для Apple Silicon и Intel |
| Только GUI для Windows/Linux | `./make_release.sh 3.10.0 --windows --linux` | Два ZIP для x64 |

Без флагов всегда используется старый формат. Флаги платформ сами включают новый
формат и могут сочетаться. Windows ARM и Linux ARM не собираются.

**GUI-инсталляторы пересобираются из исходников для каждого релиза вместе с полным
payload.** Каждый содержит Full, Light и единое удаление, APK, ADB, hooks и остальные
файлы. Windows-пакет включает offline WebView2. Пользователь не скачивает отдельный
payload и не устанавливает ADB. Linux GUI требует графической системы с X11/XWayland
и glibc уровня Ubuntu 22.04; необходимые USB-драйверы относятся к окружению ОС.

Готовые установщики в Git не хранятся. Ручного манифеста для выпуска нет:
служебные JSON внутри payload генерирует сборщик.

## 2. Один раз подготовить окружение

### 2.1. Выбрать сборочную машину

Основной путь выпуска обоих форматов — **macOS**. Старый shell-сценарий можно
запускать также в Linux с Android SDK. Оркестратор нового релиза
`make_release.sh --installers` сейчас требует macOS: Windows/Linux он собирает
в подготовленных Linux-контейнерах Colima.

На Windows/Linux можно нативно пересобрать установщик своей ОС из готового payload:
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

Теперь можно выполнять `./make_release.sh 3.10.0 --mac`. При выборе только macOS
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
ADB и упаковочные инструменты. Автономность относится к готовому установщику.

### 2.5. Что переносить на другой компьютер

Кроме checkout, сохраните **действующий** `~/.android/debug.keystore`.
Сейчас release-сборки Native и RestoreMode используют debug signing config.
Без этого файла новый компьютер сгенерирует другой ключ.

При известном несовпадении подписи GUI/CLI удаляет соответствующее приложение
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
| Исполняемый процесс GUI/CLI | `Installer/crates/installer-core/src/engine.rs` |
| Команды автомобиля / отображаемые шаги | `classic_commands.rs`, `plans.rs` в той же папке |
| Интерфейс | `Installer/desktop/src/` |
| Общая иконка | `Packaging/branding/app-icon.png` |
| Описание изменений / инструкция пользователю | `hownews.md`, `Packaging/README.txt` |

Новая сборка подхватывает изменения существующих файлов. Frida и DNS overlay
берутся из Packaging готовыми. Для замены DNS APK следуйте
[инструкции overlay](../Packaging/vendor-overlay/README.md), включая закреплённые суммы.
Иконки экспортируются отдельно: `node Installer/scripts/generate-icons.mjs`;
зависимости описаны в [инструкции branding](../Packaging/branding/README.md).

**При добавлении или удалении компонента обновите действия установки и удаления.**
GUI-сборщик обнаруживает `.js/.json` в `Packaging/inject/`, но попадание файла в
payload само по себе не добавляет действие в Rust-процесс. Для новых собственных
имён используйте `voyahtune-…` / `voyahtune_…`. Проверьте раскладку в `make_release.sh`,
старые `.sh/.bat`, Rust-порт и очистку. Состав других типов файлов задаёт
`Installer/crates/installer-build/src/main.rs`.

GUI выполняет перенесённую в Rust логику классических скриптов с прогрессом
по шагам. Дополнительные проверки автомобиля и причины остановки, отсутствующие
в классическом процессе, добавлять нельзя. Изменения процесса вносите в оба
формата и проверяйте тестами соответствия. [Карта процесса](installer-classic-port.md).

`manifest.json` и другие JSON внутри сборочного payload создаются автоматически:
их не правят вручную. Не редактируйте `Releases/` вместо исходников.

### Версии и подписи

1. Выберите SemVer-версию комплекта, например `3.10.0`. Она передаётся аргументом
   `make_release.sh`; `@VERSION@` в исходных скриптах заменяется автоматически.
2. Для изменившихся APK увеличьте `versionCode` относительно опубликованного
   выпуска и задайте `versionName` в `Native/app/build.gradle.kts` и
   `RestoreMode/app/build.gradle.kts`. Номер комплекта сам эти поля не меняет.
3. При изменениях движка обновите `workspace.package.version` в
   `Installer/Cargo.toml` и записи локальных пакетов в `Installer/Cargo.lock`.
   Это отдельная версия движка; версия desktop-пакета берётся из payload.
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
./make_release.sh 3.10.0
```

Скрипт запускает проверки Packaging, собирает Full/Light APK обоих приложений,
раскладывает файлы и создаёт:

```text
Releases/dist/VoyahTune-3.10.0.zip
Releases/dist/VoyahTune-3.10.0-light.zip
Releases/build/VoyahTune-3.10.0/
Releases/build/VoyahTune-3.10.0-light/
```

В каждом ZIP — плоская папка с APK, ресурсами, `install.sh/.bat`, `remove.sh/.bat`
и README. Windows ADB входит в старый комплект; Unix-скрипты используют `adb`
из PATH. Встраивание ADB для всех ОС относится к новому формату.

| Команда | Назначение |
| --- | --- |
| `./make_release.sh 3.10.0 --full-only` | Только Full |
| `./make_release.sh 3.10.0 --light-only` | Только Light |
| `./make_release.sh 3.10.0 --no-zip` | Собрать APK и папки, без новых ZIP |
| `./make_release.sh 3.10.0 --no-build` | Перепаковать с APK из существующих папок этой версии в `Releases/build/` |

`--no-build` не обновляет APK из изменённых исходников; для нового выпуска
используйте обычную команду. `--legacy VERSION` остаётся совместимым псевдонимом
старого режима.

## 5. Собрать по новому: автономные GUI/CLI

```sh
./make_release.sh 3.10.0 --installers
```

Команда собирает четыре APK с метаданными выпуска, формирует и проверяет payload,
пересобирает desktop-приложения и встраивает файлы с ADB:

```text
Releases/dist/VoyahTune-3.10.0-installers/
  VoyahTune-3.10.0-macos.zip
  VoyahTune-3.10.0-windows.zip
  VoyahTune-3.10.0-linux.zip
  SHA256SUMS
  release.json
Releases/build/installer-payload-3.10.0/
```

В macOS ZIP находится `.app`, в Windows ZIP — NSIS `.exe`, в Linux ZIP — `.run`.
Каждый включает полный payload и выбор Full/Light/удаления. Папку
`installer-payload-3.10.0` пользователю передавать не нужно.

Выбор платформ:

```sh
./make_release.sh 3.10.0 --mac
./make_release.sh 3.10.0 --windows
./make_release.sh 3.10.0 --linux
./make_release.sh 3.10.0 --mac --windows
```

### Повторная сборка и специальные режимы

Существующий локальный релиз не мешает повторной сборке: результат перезаписывается.
В GUI-режиме каталог выпуска заменяется целиком после успеха. Например, `--mac`
оставит только новый macOS ZIP и метаданные; прежние Windows/Linux ZIP удаляются
из этого каталога. Для общего набора выбирайте платформы одной командой.

В старом режиме замена выполняется отдельно для Full/Light: если Full завершён,
а Light упал, Full уже обновлён. `--no-zip` не обновляет ранее созданные ZIP —
не выдавайте их за результат новой сборки.

| Команда | Назначение |
| --- | --- |
| `./make_release.sh 3.10.0 --installers --no-zip` | Собрать и проверить только payload, без desktop и ZIP |
| `./make_release.sh 3.10.0 --mac --no-build` | Использовать прежние Gradle APK с совпадающими release-метаданными; desktop всё равно пересобрать |

`--no-build` нового формата требует совпадения версии, revision и состава
с метаданными APK. Для обычного выпуска его не используйте. `--full-only` и
`--light-only` относятся только к старому формату: GUI всегда содержит оба набора.

Если изменён только интерфейс и нужен установщик с прежним автомобильным
комплектом, пересоберите desktop из готового payload:

```sh
./Installer/scripts/build-all-macos.sh --mac \
  --payload Releases/build/installer-payload-3.10.0
```

Результат — `Releases/build/installers-3.10.0/` с `macos-universal.tar.gz` и
`build-info.json`; без `--mac` там будут также `windows-x64.exe` и `linux-x64.run`.
Эта низкоуровневая команда не обновляет release ZIP в `Releases/dist/`.
Логи desktop-сборок: `Releases/cache/installer-all-*/`.

Для обоих форматов выполните последовательно:

```sh
./make_release.sh 3.10.0
./make_release.sh 3.10.0 --installers
```

Не запускайте сборки параллельно в одном checkout: Gradle/target-каталоги общие.
Скрипты не публикуют релиз, не создают тег и не устанавливают его на автомобиль.

## 6. Проверить и передать релиз

1. Проверьте успешное завершение команды и наличие ожидаемых ZIP.
   Для GUI проверьте `releaseVersion`, `buildRevision`, `engineVersion`,
   `embeddedPayload: true` и список платформ в `release.json`.
2. Проверьте сертификаты APK и описание изменений.
3. Для GUI выполните из каталога выпуска `shasum -a 256 -c SHA256SUMS` на macOS
   или `sha256sum -c SHA256SUMS` на Linux. В Windows сравните результаты
   `Get-FileHash -Algorithm SHA256` с файлом сумм.
4. Распакуйте ZIP на каждой целевой ОС. Для GUI проверьте запуск без внешнего
   payload, версию, выбор набора/удаления, ручное подтверждение автомобиля,
   кнопку «Обновить», журнал и обновление шагов. Отдельно проверьте CLI.
5. На тестовом автомобиле проверьте Full, Light, обновление прежней версии,
   переходы Full ↔ Light и удаление. При том же ключе — сохранение настроек;
   при смене ключа GUI — согласованную переустановку с очисткой данных.

Явная проверка встроенного комплекта:

```sh
# macOS: путь к распакованному приложению
'/path/to/VoyahTune Installer.app/Contents/MacOS/voyahtune' verify
# Linux: путь к распакованному .run
./VoyahTune-Installer.run --cli verify
```

Windows: `voyahtune.exe verify` из каталога установленного инструмента.
`/S` у NSIS выполняет тихую установку самого инструмента на компьютер;
действия с автомобилем автоматизируются через встроенный CLI.

При изменениях установщика выполните проверки из
[карты Rust-порта](installer-classic-port.md#как-проверять-изменения).
Fake ADB проверяет соответствие скриптам, но не заменяет реальную ОС и автомобиль.
Developer ID/notarization и Authenticode пока не настроены.

Опубликуйте проверенные ZIP, описание выпуска и для GUI — `SHA256SUMS` и
`release.json`; при необходимости создайте тег на соответствующий коммит.
В Git сохраняйте исходники, ресурсы и скрипты, без сгенерированных APK,
готовых установщиков и содержимого `Releases/`. Уже опубликованную версию
не подменяйте: для изменившегося публичного комплекта используйте новый номер.
