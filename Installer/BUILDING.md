# Сборка VoyahTune Installer

GUI общий для PI и OD и выпускается независимо от автомобильного релиза.
Команда `make_release.sh` требует ровно один `--pi` или `--od`; суффикс профиля
становится частью версии (`3.22.0-pi`/`3.22.0-od`). Самостоятельные GUI-сборки
`build-all-macos.sh` и `build.mjs` не требуют профиля: один установщик поддерживает
оба payload. В общем каталоге пользователь выбирает нужную версию, backend
определяет инфраструктуру по проверенному manifest. Новый контракт требует
Installer 1.5.0 и capability `infrastructure-v1`.

Для нового payload:

```sh
./make_release.sh 3.22.0 --od --payload
```

Для самостоятельного macOS Universal GUI без APK:

```sh
./Installer/scripts/build-all-macos.sh --mac
```

Результат: `Releases/build/installers-1.5.0/macos-universal.tar.gz` и `build-info.json`.
Версия GUI берётся из Cargo. При необходимости offline bundle задайте `--payload DIRECTORY`;
обычная сборка включает только ADB и небольшой remover. Пользователь выбирает/скачивает
версию в GUI. Пользовательского CLI нет, `installer-build` остаётся инструментом разработчика.

`./make_release.sh VERSION --od --mac` сохраняется как обёртка: собирает payload ZIP и отдельно
macOS GUI ZIP в `Releases/dist/VoyahTune-Installer-1.5.0/`. `--installers` по умолчанию
выбирает все платформы; для одной macOS используйте `--mac`.
Подробности и публикация: [Docs/releasing.md](../Docs/releasing.md).

## Полная сборка из macOS

Обёртка `make_release.sh VERSION --od --installers` собирает общие Native/RestoreMode APK, дополнительно RunYN для PI,
проверенный ZIP payload и самостоятельные GUI. Результаты разделены:
`Releases/dist/payload_VERSION.zip` (VERSION включает профиль) и `Releases/dist/VoyahTune-Installer-INSTALLER_VERSION/`.

Флаги `--mac`, `--windows`, `--linux` выбирают платформы и могут сочетаться.
В `make_release.sh` они включают режим установщиков без отдельного `--installers`:

```sh
./make_release.sh 3.22.0 --od --mac
./make_release.sh 3.22.0 --od --windows --linux
./Installer/scripts/build-all-macos.sh --mac --payload Releases/build/installer-payload-3.22.0-od
```

Без выбора платформ `--installers` собирает все три. `--mac` сохраняет Universal
ARM64+x86-64, Windows по умолчанию x64, `--windows-arch x86` выбирает x86; Linux — x64. Для `--mac` Docker/Colima не нужны:
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
  --payload Releases/build/installer-payload-3.22.0-od
```

Результат этой низкоуровневой команды — `Releases/build/installers-1.5.0/`:
macos-universal.tar.gz, windows-x64.exe, linux-x64.run и build-info.json.
Можно указать другой каталог внутри Releases через `--output`.
Существующий результат заменяется после успешной сборки всех выбранных платформ;
при ошибке прежний релиз сохраняется. Логи: `Releases/cache/installer-all-XXXXXX/`.

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
./make_release.sh 3.22.0 --od --installers
```

Скрипт сам копирует исходники Installer и опциональный payload в каталоги `hosts/`;
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
  --payload Releases/build/installer-payload-3.22.0-od
```

Результат: `Installer/target/universal-apple-darwin/release/bundle/macos/VoyahTune Installer.app`.
Этот target игнорируется Git. Скрипт объединяет обе архитектуры GUI, включает
Google ADB и ресурсы удаления (payload опционален), проверяет релиз внутри `.app`.

Проверки:

```sh
lipo -archs 'Installer/target/universal-apple-darwin/release/bundle/macos/VoyahTune Installer.app/Contents/MacOS/voyahtune-desktop'
Installer/target/release/installer-build verify-host \
  'Installer/target/universal-apple-darwin/release/bundle/macos/VoyahTune Installer.app/Contents/Resources/bundle'
```

Если Rust хранится в проектном кэше, build.mjs находит его автоматически. Для ручных
cargo/rustup-команд задайте CARGO_HOME=`<repo>/Releases/cache/cargo`,
RUSTUP_HOME=`<repo>/Releases/cache/rustup` и добавьте `$CARGO_HOME/bin` в PATH.
Developer ID / notarization пока не настроены.

## Windows: инструменты и отдельная сборка

Нужны Windows 10/11 x64, Node.js 22/npm, Rust/rustup, Visual Studio 2022 Build Tools
с Desktop development with C++, MSVC x64/x86 и Windows SDK. Из Developer PowerShell:

```powershell
rustup toolchain install 1.98.1 --profile minimal
rustup target add --toolchain 1.98.1 x86_64-pc-windows-msvc
node Installer/scripts/build.mjs --target x86_64-pc-windows-msvc --payload 'C:\path\to\payload'
```

При нативной сборке результат:
`Installer/target/release/bundle/nsis/VoyahTune Installer_<версия>_x64-setup.exe`.
При кросс-сборке — `Installer/target/x86_64-pc-windows-msvc/release/bundle/nsis/`.
Имя версии берётся из Cargo. NSIS включает GUI, ADB и ресурсы удаления. WebView2 не включён: режим
`downloadBootstrapper` скачивает и устанавливает его через интернет, только если
runtime отсутствует на компьютере. Отдельного копирования соседней папки после установки больше нет.
`/S` поддерживает тихую установку самого инструмента; операции с автомобилем доступны в GUI.
Authenticode не настроен; запуск на настоящей Windows ещё требует проверки.

В Linux-контейнере для кросс-сборки дополнительно:

```sh
cargo install --locked cargo-xwin
rustup target add --toolchain 1.98.1 x86_64-pc-windows-msvc
node Installer/scripts/build.mjs --target x86_64-pc-windows-msvc --payload /work/path/to/payload
```

### Windows x86 (32 бита)

Для Windows 10 x86 используйте target `i686-pc-windows-msvc`. В подготовленном
окружении macOS отдельный установщик собирается командой:

```sh
./Installer/scripts/build-all-macos.sh --windows-arch x86
```

Результат: `Releases/build/installers-<версия>-windows-x86/windows-x86.exe`.
Без `--windows-arch` Windows собирается для x64. Rust target для выбранной
архитектуры устанавливается автоматически; `--check` только проверяет его наличие.

Для прямой сборки на Windows или в Linux-контейнере:

```sh
rustup target add --toolchain 1.98.1 i686-pc-windows-msvc
node Installer/scripts/build.mjs --target i686-pc-windows-msvc
```

При кросс-сборке сборщик выбирает x86 SDK для cargo-xwin и отдельный кэш
`Releases/cache/cargo-xwin-x86` (можно переопределить через `XWIN_CACHE_DIR`).
Сборщик проверяет, что встроенные ADB EXE и DLL тоже имеют архитектуру x86.
Payload общий с x64 и macOS; отдельные APK или каталог для x86 не нужны.
WebView2 скачивается при необходимости. Windows 11 не имеет 32-битного выпуска ОС;
для 64-битной Windows используйте установщик x64. Проверка архива и архитектуры
при сборке не заменяет проверку запуска GUI и подключения ADB на Windows 10 x86.

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
./Releases/dist/VoyahTune-Installer.run
```

`/opt/target` должен быть доступен на запись. При bind mount macOS упаковка AppDir
выполняется на внутренней Linux-ФС: virtiofs может нарушать права и симлинки.
Общая команда автоматически компилирует с кэшем, затем упаковывает внутри `/opt`.
Ресурсы находятся в `usr/share/voyahtune-installer/bundle`, чтобы linuxdeploy
не принимал Android ELF за библиотеки Linux. Проверка после упаковки обнаруживает
изменение файлов и хешей. `.run` работает без FUSE; `--extract NEW_DIRECTORY`
извлекает весь AppDir. GUI требует графической сессии.
Для GUI нужен desktop/X11 или XWayland и glibc уровня Ubuntu22.04.

## Закреплённый linuxdeploy для Rosetta

У AppImage runtime linuxdeploy и output plugin есть несовместимость с Rosetta.
Общая команда обходит её автоматически: проверяет SHA, извлекает SquashFS напрямую,
запускает внутренний AppRun и задаёт PATH для GTK/GStreamer plugins. Затем создаёт
`.run`, которому AppImage runtime не нужен. В извлечённом linuxdeploy используются
`/usr/bin/patchelf` и `/usr/bin/strip` подготовленного контейнера: его статические
встроенные копии также дали сбой под эмуляцией. Ошибки обработки библиотек
не игнорируются; после упаковки проверяются вложенные ресурсы.

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
нативного пакета берётся из Cargo, автомобильного релиза — из payload.
Готовые binaries, validators, tooling.json в Packaging больше не используются.
Публикуемые файлы — только результаты из Releases/dist.

Проверяйте `installer-build verify-host PATH`, запуск на целевых ОС, установку, обновление и удаление.
Успешная кросс-сборка Windows и fake ADB не заменяют проверки на реальной ОС и машине.

## Web-интерфейс при сборке через macOS

`build-all-macos.sh` собирает общий Svelte/Vite интерфейс нативно на macOS и передаёт
готовый `dist` контейнерам Windows/Linux. Rust и упаковка выполняются в соответствующем
контейнере. Это исключает запуск Go runtime esbuild под эмуляцией x64, где наблюдался
сбой сборщика мусора. При самостоятельной нативной сборке `build.mjs` по-прежнему
собирает web-интерфейс обычным способом; `--frontend-dist PATH` разрешает явно передать
свежую сборку и требует наличие `index.html`.
