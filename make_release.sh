#!/bin/sh
# Сборка релиза Open Voyah: собирает APK обоих флейворов и раскладывает готовые папки релиза.
#
#   ./make_release.sh 3.2.2              → Releases/build/v3.2.2{,-light} + Releases/dist/*.zip
#   ./make_release.sh 3.2.2 --full-only  → только full
#   ./make_release.sh 3.2.2 --light-only → только light
#   ./make_release.sh 3.2.2 --no-build   → не пересобирать APK, только переразложить файлы
#                                          (APK берутся из уже существующей папки сборки)
#   ./make_release.sh 3.2.2 --no-zip     → не паковать архивы
#
# Источник всего, кроме APK — Packaging/ (см. Packaging/README.md). Он В GIT.
# Releases/ — ТОЛЬКО вывод и целиком в .gitignore: сборки в Releases/build/, готовые к
# раздаче архивы в Releases/dist/. В репозитории релизы больше не хранятся.
# Папка релиза остаётся ПЛОСКОЙ: install.sh ищет файлы рядом с собой, его править не нужно.
# Заменяет собой прежние build_full.sh / build_light.sh (там версия была зашита в код).
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
COMMON="$ROOT/Packaging"
BUILD="$ROOT/Releases/build"
DIST="$ROOT/Releases/dist"
DNS_OVERLAY_NAME="framework-res__config_ethernet_interfaces_yandexdns.apk"
DNS_OVERLAY="$COMMON/vendor-overlay/$DNS_OVERLAY_NAME"
DNS_OVERLAY_SHA256="c4694866ff920b2409ce58d3dd4c84b86ba102049b68d27a6998ef91d7a0308d"
COMMON_INSTALLER="$COMMON/installer/common"
COMMON_INSTALLER_FILES="dns-overlay.sh dns-overlay.bat select-yandex-dns.ps1 dns-overlay-device.sh"

VERSION=""
DO_FULL=1
DO_LIGHT=1
DO_BUILD=1
DO_ZIP=1

for arg in "$@"; do
    case "$arg" in
        --full-only)  DO_LIGHT=0 ;;
        --light-only) DO_FULL=0 ;;
        --no-build)   DO_BUILD=0 ;;
        --no-zip)     DO_ZIP=0 ;;
        -h|--help)    sed -n '2,16p' "$0"; exit 0 ;;
        -*)           echo "Неизвестный флаг: $arg" >&2; exit 1 ;;
        *)            VERSION="$arg" ;;
    esac
done

if [ -z "$VERSION" ]; then
    echo "Не указана версия. Пример: ./make_release.sh 3.2.2" >&2
    exit 1
fi
# Версию принимаем и как «3.2.2», и как «v3.2.2» — нормализуем к виду без префикса.
VERSION="${VERSION#v}"

if [ ! -d "$COMMON" ]; then
    echo "Нет $COMMON — папка-источник комплекта релиза отсутствует." >&2
    exit 1
fi

# SHA-256 для зафиксированных бинарных артефактов. GNU/Linux обычно предоставляет sha256sum,
# macOS — shasum; если нет ни одного, продолжать сборку с непроверенным APK нельзя.
sha256_file() {
    file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{print $1}'
    else
        echo "Не найден ни sha256sum, ни shasum — невозможно проверить $file." >&2
        return 1
    fi
}

# Проверяем общие готовые артефакты ДО запуска Gradle и создания содержимого релиза.
verify_common_release_assets() {
    if [ ! -f "$DNS_OVERLAY" ]; then
        echo "Нет $DNS_OVERLAY — добавьте зафиксированный DNS RRO APK." >&2
        exit 1
    fi

    actual_sha256="$(sha256_file "$DNS_OVERLAY")"
    if [ "$actual_sha256" != "$DNS_OVERLAY_SHA256" ]; then
        echo "Неверный SHA-256 у $DNS_OVERLAY:" >&2
        echo "  ожидался: $DNS_OVERLAY_SHA256" >&2
        echo "  получен:  $actual_sha256" >&2
        exit 1
    fi

    if [ ! -d "$COMMON_INSTALLER" ]; then
        echo "Нет $COMMON_INSTALLER — отсутствуют общие helper-файлы установщика." >&2
        exit 1
    fi

    for helper in $COMMON_INSTALLER_FILES; do
        if [ ! -f "$COMMON_INSTALLER/$helper" ]; then
            echo "Нет обязательного helper-файла $COMMON_INSTALLER/$helper." >&2
            exit 1
        fi
    done

    # Hash намеренно продублирован в host/device helpers, которые работают уже вне build tree.
    # Не позволяем обновить prebuilt только в одном месте и собрать заведомо нерабочий релиз.
    for helper in dns-overlay.sh dns-overlay.bat dns-overlay-device.sh; do
        hash_mentions="$(grep -F -c "$DNS_OVERLAY_SHA256" "$COMMON_INSTALLER/$helper" || true)"
        if [ "$hash_mentions" -ne 1 ]; then
            echo "В $COMMON_INSTALLER/$helper должен быть ровно один актуальный DNS RRO SHA-256." >&2
            exit 1
        fi
    done
}

# Общие для full/light файлы попадают в плоский корень релиза.
copy_common_release_assets() {
    out="$1"
    cp -p "$DNS_OVERLAY" "$out/$DNS_OVERLAY_NAME"
    for helper in $COMMON_INSTALLER_FILES; do
        case "$helper" in
            *.bat) copy_crlf "$COMMON_INSTALLER/$helper" "$out/$helper" ;;
            *)     cp -p "$COMMON_INSTALLER/$helper" "$out/$helper" ;;
        esac
    done
}

verify_common_release_assets

# Упаковать папку релиза в архив для раздачи. Архив содержит одну папку верхнего уровня
# (VoyahTune-<версия>), чтобы у пользователя при распаковке не разъезжались файлы по Загрузкам.
make_zip() {
    dir="$1"; name="$2"
    [ "$DO_ZIP" = 1 ] || return 0
    command -v zip >/dev/null || { echo "  zip не найден — архив пропущен"; return 0; }
    mkdir -p "$DIST"
    rm -f "$DIST/$name.zip"
    # -q тихо, -r рекурсивно; пакуем ИМЕНЕМ папки, поэтому идём в родителя.
    (cd "$(dirname "$dir")" && zip -qr "$DIST/$name.zip" "$(basename "$dir")")
    echo "  архив → Releases/dist/$name.zip ($(du -h "$DIST/$name.zip" | cut -f1))"
}

# Скопировать Windows batch с обязательными CRLF. cmd.exe некорректно разбирает некоторые
# LF-only .bat: в частности, может терять первый символ команды (echo -> cho, pause -> ause).
# Нормализуем здесь, а не полагаемся на git checkout/autocrlf машины, где собирается релиз.
copy_crlf() {
    src="$1"; dst="$2"
    cp -p "$src" "$dst"
    LC_ALL=C awk '{ sub(/\r$/, ""); printf "%s\r\n", $0 }' "$src" > "$dst"
}

# Скопировать файл и проставить версию вместо плейсхолдера @VERSION@ (он есть в шапке установщиков).
copy_stamped() {
    src="$1"; dst="$2"
    # cp -p одинаково работает с BSD cp (macOS) и GNU cp (Debian) и сохраняет права.
    # После копирования перенаправление только обнуляет содержимое, не меняя режим файла.
    cp -p "$src" "$dst"
    case "$dst" in
        *.bat)
            sed "s/@VERSION@/$VERSION/g" "$src" |
                LC_ALL=C awk '{ sub(/\r$/, ""); printf "%s\r\n", $0 }' > "$dst"
            ;;
        *) sed "s/@VERSION@/$VERSION/g" "$src" > "$dst" ;;
    esac
}

# Защита релизного архива: каждый физический перевод строки в .bat обязан быть CRLF.
verify_windows_batch_files() {
    out="$1"
    for batch in "$out/"*.bat; do
        [ -f "$batch" ] || continue
        if ! LC_ALL=C awk '
            { if (!sub(/\r$/, "")) exit 1 }
            END { if (NR == 0) exit 1 }
        ' "$batch"; then
            echo "Некорректные переводы строк в $batch — .bat должен использовать CRLF." >&2
            exit 1
        fi
    done
}

# Собрать APK одного флейвора и положить в папку релиза под финальными именами.
# $1 = full|light, $2 = папка релиза
build_apks() {
    flavor="$1"; out="$2"
    # assembleFullRelease / assembleLightRelease — первая буква флейвора в верхнем регистре.
    case "$flavor" in
        full)  task="assembleFullRelease" ;;
        light) task="assembleLightRelease" ;;
    esac

    echo "== Native: $task =="
    (cd "$ROOT/Native" && ./gradlew "$task" -q)
    cp "$ROOT/Native/app/build/outputs/apk/$flavor/release/app-$flavor-release.apk" "$out/native.apk"

    echo "== RestoreMode: $task =="
    (cd "$ROOT/RestoreMode" && ./gradlew "$task" -q)
    cp "$ROOT/RestoreMode/app/build/outputs/apk/$flavor/release/app-$flavor-release.apk" "$out/restore_mode.apk"
}

# Проверка, что в папке релиза лежат APK — при --no-build мы их не собираем, но релиз без них невалиден.
require_apks() {
    out="$1"
    for f in native.apk restore_mode.apk; do
        if [ ! -f "$out/$f" ]; then
            echo "Нет $out/$f — с --no-build APK должны уже лежать в папке релиза." >&2
            exit 1
        fi
    done
}

# ---------------------------------------------------------------------------------------------
# FULL: полный набор — инжект-скрипты, boot-обвязка, frida, Windows-инструменты.
# ---------------------------------------------------------------------------------------------
if [ "$DO_FULL" = 1 ]; then
    OUT="$BUILD/VoyahTune-$VERSION"
    echo ""
    echo "########## FULL → Releases/build/VoyahTune-$VERSION ##########"
    mkdir -p "$OUT"

    if [ "$DO_BUILD" = 1 ]; then build_apks full "$OUT"; else require_apks "$OUT"; fi

    cp "$COMMON/tools/"*                                    "$OUT/"
    cp "$COMMON/inject/"*.js                                "$OUT/"
    cp "$COMMON/system/"*                                   "$OUT/"
    copy_common_release_assets "$OUT"
    for f in "$COMMON/installer/full/"*; do
        copy_stamped "$f" "$OUT/$(basename "$f")"
    done
    verify_windows_batch_files "$OUT"

    echo "FULL готов → $OUT"
    make_zip "$OUT" "VoyahTune-$VERSION"
fi

# ---------------------------------------------------------------------------------------------
# LIGHT: не-root набор — без инжекта, frida, load.bin и boot-хука.
# Инструменты берём НЕ целиком: нужен только adb (его требуют .bat на Windows; на Unix .sh
# рассчитывает на системный adb). frida-inject в light не кладём — он весит 53M и здесь не нужен.
# ---------------------------------------------------------------------------------------------
LIGHT_TOOLS="adb.exe AdbWinApi.dll AdbWinUsbApi.dll"

if [ "$DO_LIGHT" = 1 ]; then
    OUT="$BUILD/VoyahTune-$VERSION-light"
    echo ""
    echo "########## LIGHT → Releases/build/VoyahTune-$VERSION-light ##########"
    mkdir -p "$OUT"

    if [ "$DO_BUILD" = 1 ]; then build_apks light "$OUT"; else require_apks "$OUT"; fi

    for t in $LIGHT_TOOLS; do
        [ -f "$COMMON/tools/$t" ] || { echo "Нет $COMMON/tools/$t" >&2; exit 1; }
        cp "$COMMON/tools/$t" "$OUT/"
    done
    cp "$COMMON/system/privapp-permissions-ru.big.town.anative.xml" "$OUT/"
    copy_common_release_assets "$OUT"
    for f in "$COMMON/installer/light/"*; do
        copy_stamped "$f" "$OUT/$(basename "$f")"
    done
    verify_windows_batch_files "$OUT"

    echo "LIGHT готов → $OUT"
    make_zip "$OUT" "VoyahTune-$VERSION-light"
fi

echo ""
echo "=== Состав релиза ==="
[ "$DO_FULL" = 1 ]  && ls -1 "$BUILD/VoyahTune-$VERSION"
[ "$DO_LIGHT" = 1 ] && { echo "--- light:"; ls -1 "$BUILD/VoyahTune-$VERSION-light"; }
echo ""
echo "Не забыть: описание версии в hownews.md."
