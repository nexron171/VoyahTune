# Сборка автономных установщиков

Готовые установщики не хранятся в Git. Их собирают из исходников для каждого
автомобильного релиза вместе со всем payload:

```sh
./make_release.sh 3.3.0 --installers
```

Без флага make_release.sh выпускает обычные Full/Light ZIP со скриптами.
[Пошаговая инструкция выпуска](../Docs/releasing.md).

## Полная сборка из macOS

На подготовленном Mac команда выше собирает четыре Android APK, автоматически
определяет состав Packaging, проверяет подписи/хеши и запускает три desktop-сборки.
Результаты: `Releases/dist/VoyahTune-3.3.0-installers/`. В ZIP нет внешнего payload:
он находится внутри `.app`, Windows NSIS и Linux `.run`.

Флаги `--mac`, `--windows`, `--linux` выбирают платформы и могут сочетаться.
В `make_release.sh` они включают режим установщиков без отдельного `--installers`:

```sh
./make_release.sh 3.3.0 --mac
./make_release.sh 3.3.0 --windows --linux
./Installer/scripts/build-all-macos.sh --mac --payload Releases/build/installer-payload-3.3.0
```

Без выбора платформ `--installers` собирает все три. `--mac` сохраняет Universal
ARM64+x86-64, Windows/Linux — только x64. Для `--mac` Docker/Colima не нужны:
они не проверяются и не запускаются. Повторный выпуск той же версии перезаписывает
локальный payload и каталог выпуска; ZIP остаются только для выбранных платформ.

Для проверки среды без компиляции:

```sh
./Installer/scripts/build-all-macos.sh --check
./Installer/scripts/build-all-macos.sh --check --mac  # только окружение macOS
```

Если проверенный payload уже подготовлен, можно пересобрать только установщики:

```sh
./Installer/scripts/build-all-macos.sh \
  --payload Releases/build/installer-payload-3.3.0
```

Результат этой низкоуровневой команды — `Releases/build/installers-3.3.0/`:
macos-universal.tar.gz, windows-x64.exe, linux-x64.run и build-info.json.
Можно указать другой каталог внутри Releases через `--output`.
Существующий результат заменяется после успешной сборки всех выбранных платформ;
при ошибке прежний комплект сохраняется. Логи: `Releases/cache/installer-all-XXXXXX/`.

Скрипт использует Colima profile `v` в `Releases/cache/colima` и два постоянных
контейнера. Он запускает среду при необходимости и останавливает только то,
что запустил сам. Подготовленные ранее запущенные VM/контейнеры остаются работать.
Не запускайте одновременно другие сборки из тех же target-каталогов.

| Контейнер | Архитектура Linux | Bind mount в `/work` |
| --- | --- | --- |
| `vti-windows` | ARM64 на Apple Silicon или x64 на Intel | `<repo>/Releases/build/hosts/windows` |
| `vti-linux-amd64` | x86-64 | `<repo>/Releases/build/hosts/linux-amd64` |

Образы собираются из `Installer/build-env/Dockerfile`. Контейнеры должны сохранять
кэши между запусками; используйте `sleep infinity`, без `--rm`. Windows-контейнеру
нужны cargo-xwin и target x86_64-pc-windows-msvc. На Apple Silicon включите Rosetta
в Colima (VZ) и подготовьте закреплённый linuxdeploy, описанный ниже. Скрипт не
создаёт отсутствующие контейнеры и не пересобирает их образы автоматически.

## Подготовка Colima и контейнеров с нуля

Этот раздел нужен только для Windows/Linux-сборок из macOS. Для `--mac` достаточно
локальных инструментов из следующего раздела. Команды выполняются из корня
checkout после установки Docker CLI, Colima и rsync. Они создают постоянную среду;
повторять создание одноимённых контейнеров перед каждым релизом не нужно.

### 1. Создать профиль VM

Пример ресурсов: 6 CPU, 8 GiB RAM и 100 GiB диска; настройте их под свой компьютер.
На Apple Silicon должна быть установлена Rosetta. Для нового профиля:

```sh
export COLIMA_HOME="$PWD/Releases/cache/colima"
mkdir -p "$COLIMA_HOME"

# Apple Silicon:
colima start v --vm-type vz --vz-rosetta --cpu 6 --memory 8 --disk 100 \
  --mount "$PWD:w"

# Intel: вместо предыдущей команды, без Rosetta:
# colima start v --vm-type vz --cpu 6 --memory 8 --disk 100 --mount "$PWD:w"
```

Все Docker-команды ниже явно используют сокет этого профиля. Функцию `vti_docker`
задайте в том же терминале; она не меняет глобальный Docker context:

```sh
vti_docker() {
  docker --host "unix://$COLIMA_HOME/v/docker.sock" "$@"
}
vti_docker info
mkdir -p Releases/build/hosts/windows Releases/build/hosts/linux-amd64
```

### 2. Подготовить Windows builder

Образ собирается для архитектуры самой VM: ARM64 на Apple Silicon либо x64
на Intel. Cargo-xwin создаёт Windows x64 результат в обоих случаях.

```sh
vti_docker build -t voyahtune-builder:host -f Installer/build-env/Dockerfile .
vti_docker run -d --name vti-windows \
  -v "$PWD/Releases/build/hosts/windows:/work" \
  voyahtune-builder:host sleep infinity
vti_docker exec vti-windows cargo install --locked cargo-xwin
vti_docker exec vti-windows rustup target add \
  --toolchain 1.98.1 x86_64-pc-windows-msvc
```

### 3. Подготовить Linux x64 builder

```sh
vti_docker build --platform linux/amd64 -t voyahtune-builder:x64 \
  -f Installer/build-env/Dockerfile .
vti_docker run -d --name vti-linux-amd64 --platform linux/amd64 \
  -v "$PWD/Releases/build/hosts/linux-amd64:/work" \
  voyahtune-builder:x64 sleep infinity
vti_docker exec vti-linux-amd64 uname -m
```

Последняя команда должна вывести `x86_64`. На Apple Silicon также подготовьте
[закреплённый linuxdeploy](#закреплённый-linuxdeploy-для-rosetta) по инструкции ниже.
При выборе одной платформы можно создать только соответствующий контейнер.

### 4. Проверить и использовать

```sh
./Installer/scripts/build-all-macos.sh --check --windows --linux
# После подготовки также Android SDK и локального Rust для macOS:
./Installer/scripts/build-all-macos.sh --check
./make_release.sh 3.3.0 --installers
```

Скрипт сам копирует исходники Installer и готовый payload в каталоги `hosts/`;
вручную заполнять их не требуется. Он проверяет точный bind mount `/work`, поэтому
после переноса checkout на другой путь контейнеры нужно пересоздать. Не направляйте
оба контейнера в один каталог. Удаление контейнера теряет его внутренний кэш;
обычно между выпусками достаточно остановки, а не удаления.

Окружение, которое уже работало до сборки, скрипт оставляет запущенным.
Созданные выше контейнеры можно остановить после работы:

```sh
vti_docker stop vti-windows vti-linux-amd64
colima stop v
```

## macOS: инструменты и отдельная сборка

Нужны Xcode Command Line Tools (`xcode-select --install`), Node.js 22 LTS/npm,
Python 3.12+, Git и Rust/rustup. Для полной release-команды дополнительно нужны
JDK 21 и Android SDK из инструкции выпуска. Для сборки только desktop с готовым
payload Android SDK/Java не требуются.

```sh
rustup toolchain install 1.98.1 --profile minimal --component rustfmt,clippy
rustup target add --toolchain 1.98.1 aarch64-apple-darwin x86_64-apple-darwin
node Installer/scripts/build.mjs --bundles app \
  --payload Releases/build/installer-payload-3.3.0
```

Результат: `Installer/target/universal-apple-darwin/release/bundle/macos/VoyahTune Installer.app`.
Этот target игнорируется Git. Скрипт объединяет обе архитектуры GUI/CLI, включает
Google ADB и payload, проверяет комплект внутри `.app`.

Проверки:

```sh
lipo -archs 'Installer/target/universal-apple-darwin/release/bundle/macos/VoyahTune Installer.app/Contents/MacOS/voyahtune-desktop'
'Installer/target/universal-apple-darwin/release/bundle/macos/VoyahTune Installer.app/Contents/MacOS/voyahtune' verify
```

Если Rust хранится в проектном кэше, build.mjs находит его автоматически. Для ручных
cargo/rustup-команд задайте CARGO_HOME=`<repo>/Releases/cache/cargo`,
RUSTUP_HOME=`<repo>/Releases/cache/rustup` и добавьте `$CARGO_HOME/bin` в PATH.
Developer ID / notarization пока не настроены.

## Windows: инструменты и отдельная сборка

Нужны Windows 10/11 x64, Node.js 22/npm, Rust/rustup, Visual Studio 2022 Build Tools
с Desktop development with C++, MSVC x64 и Windows SDK. Из Developer PowerShell:

```powershell
rustup toolchain install 1.98.1 --profile minimal
rustup target add --toolchain 1.98.1 x86_64-pc-windows-msvc
node Installer/scripts/build.mjs --target x86_64-pc-windows-msvc --payload 'C:\path\to\payload'
```

При нативной сборке результат:
`Installer/target/release/bundle/nsis/VoyahTune Installer_3.3.0_x64-setup.exe`.
При кросс-сборке — `Installer/target/x86_64-pc-windows-msvc/release/bundle/nsis/`.
Имя версии берётся из payload. NSIS включает offline WebView2, CLI, ADB и полный
payload. Отдельного копирования соседней папки после установки больше нет.
`/S` поддерживает тихую установку самого инструмента; CLI выполняет операции с автомобилем.
Authenticode не настроен; запуск на настоящей Windows ещё требует проверки.

В Linux-контейнере для кросс-сборки дополнительно:

```sh
cargo install --locked cargo-xwin
rustup target add --toolchain 1.98.1 x86_64-pc-windows-msvc
node Installer/scripts/build.mjs --target x86_64-pc-windows-msvc --payload /work/path/to/payload
```

## Linux: инструменты и отдельная сборка

База — Ubuntu 22.04 x86-64. Полный список build-зависимостей находится в Dockerfile:
WebKitGTK 4.1, GTK3, OpenSSL, libsoup3, компиляторы, patchelf, squashfs-tools,
Node22, Rust1.98.1, GStreamer, а также xvfb/xauth для GUI-проверок без дисплея.
Пример для нативного x64 Docker-host:

```sh
docker build --platform linux/amd64 -t voyahtune-builder:x64 -f Installer/build-env/Dockerfile .
docker run --rm -it --platform linux/amd64 -v "$PWD:/work" -w /work voyahtune-builder:x64 bash
```

Для постоянного окружения общей macOS-команды создайте контейнер с именем и mount
из таблицы выше, без `--rm`, с командой `sleep infinity`.

Внутри Linux:

```sh
CARGO_TARGET_DIR=/opt/target node Installer/scripts/build.mjs --payload /work/path/to/payload
python3 Installer/scripts/package-linux.py \
  --appdir '/opt/target/release/bundle/appimage/VoyahTune Installer.AppDir' \
  --output Releases/dist/VoyahTune-Installer.run
./Releases/dist/VoyahTune-Installer.run --cli verify
```

`/opt/target` должен быть доступен на запись. При bind mount macOS упаковка AppDir
выполняется на внутренней Linux-ФС: virtiofs может нарушать права и симлинки.
Общая команда автоматически компилирует с кэшем, затем упаковывает внутри `/opt`.
Полный payload находится в `usr/share/voyahtune-installer/bundle`, чтобы linuxdeploy
не принимал Android ELF за библиотеки Linux. Проверка после упаковки обнаруживает
изменение файлов и хешей. `.run` работает без FUSE; `--extract NEW_DIRECTORY`
извлекает весь AppDir вместе с payload. CLI не требует графической сессии.
Для GUI нужен desktop/X11 или XWayland и glibc уровня Ubuntu22.04.

## Закреплённый linuxdeploy для Rosetta

У AppImage runtime linuxdeploy и output plugin есть несовместимость с Rosetta.
Общая команда обходит её автоматически: проверяет SHA, извлекает SquashFS напрямую,
запускает внутренний AppRun и задаёт PATH для GTK/GStreamer plugins. Затем создаёт
`.run`, которому AppImage runtime не нужен.

На новом Mac подготовьте кэш (он не хранится в Git):

```sh
mkdir -p Releases/cache
curl -fL -H 'Accept: application/octet-stream' \
  https://api.github.com/repos/linuxdeploy/linuxdeploy/releases/assets/538917371 \
  -o Releases/cache/linuxdeploy-x86_64-new.AppImage.download
shasum -a 256 Releases/cache/linuxdeploy-x86_64-new.AppImage.download
```

Ожидаемый SHA-256: `36a2d7e274d12e1050d0e9ecfe11d339ed54720b2bec464c286d53f8b07f5c62`.
Только после совпадения переименуйте файл в
`Releases/cache/linuxdeploy-x86_64-new.AppImage`. Скрипт повторно проверит сумму.
Версия: commit07333c6; SquashFS offset944632 относится именно к этому файлу.
Если asset удалён, используйте проверенную копию прежнего кэша или отдельно
проверьте новый инструмент и обновите закрепление в build-all-macos.sh.

## Иконки, версии и проверки

[Общая иконка](../Packaging/branding/README.md) экспортируется отдельно через
`node Installer/scripts/generate-icons.mjs`. Во время обычной сборки используются
уже сохранённые PNG/ICO/ICNS и Android-ресурсы.

Версия движка задаётся в Installer/Cargo.toml. Версия самого устанавливаемого
комплекта и нативного пакета берётся из автоматически подготовленного payload.
Готовые binaries, validators, tooling.json в Packaging больше не используются.
Публикуемые файлы — только результаты из Releases/dist.

Проверяйте CLI verify, запуск на целевых ОС, Full/Light, обновление и удаление.
Успешная кросс-сборка Windows и fake ADB не заменяют проверки на реальной ОС и машине.
