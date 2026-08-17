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
COMMON_INSTALLER_FILES="dns-overlay.sh dns-overlay.bat install-yandex-dns.bat dns-overlay-device.sh"
RELEASE_README="$COMMON/README.txt"

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
# Ниже готовая папка заменяется целиком, поэтому имя обязано быть одним безопасным path-компонентом.
case "$VERSION" in
    [A-Za-z0-9]*) ;;
    *) echo "Недопустимая версия '$VERSION': первый символ должен быть латинской буквой или цифрой." >&2; exit 1 ;;
esac
case "$VERSION" in
    *[!A-Za-z0-9._+-]*) echo "Недопустимая версия '$VERSION': разрешены A-Z, a-z, 0-9, '.', '_', '+', '-'." >&2; exit 1 ;;
esac

STAGING_DIR=""
STAGED_OUT=""
ZIP_STAGE_DIR=""
ACTIVE_ZIP_TRACKED=0
ACTIVE_ZIP_FINAL=""
ACTIVE_ZIP_HAD_PREVIOUS=0
ACTIVE_PUBLISH=0
ACTIVE_PREVIOUS_ROOT=""
ACTIVE_FINAL_OUT=""
ACTIVE_HAD_PREVIOUS=0
RELEASE_LOCK=""
RELEASE_LOCK_HELD=0

rollback_active_zip() {
    [ "$ACTIVE_ZIP_TRACKED" = 1 ] || return 0

    if [ "$ACTIVE_ZIP_HAD_PREVIOUS" = 1 ]; then
        if [ -e "$ZIP_STAGE_DIR/previous.zip" ]; then
            # previous.zip — отдельная rollback-копия; mv -f атомарно заменяет новый ZIP старым.
            mv -f "$ZIP_STAGE_DIR/previous.zip" "$ACTIVE_ZIP_FINAL" || return 1
        elif [ ! -e "$ACTIVE_ZIP_FINAL" ]; then
            return 1
        fi
    elif [ -e "$ACTIVE_ZIP_FINAL" ]; then
        rm -f "$ACTIVE_ZIP_FINAL" || return 1
    fi

    rm -rf "$ZIP_STAGE_DIR" || return 1
    ZIP_STAGE_DIR=""
    ACTIVE_ZIP_TRACKED=0
    ACTIVE_ZIP_FINAL=""
    ACTIVE_ZIP_HAD_PREVIOUS=0
    return 0
}

rollback_active_release() {
    [ "$ACTIVE_PUBLISH" = 1 ] || return 0

    RELEASE_ROLLBACK_FAILED=0
    rollback_active_zip || RELEASE_ROLLBACK_FAILED=1
    RELEASE_FOLDER_ROLLBACK_OK=1

    if [ "$ACTIVE_HAD_PREVIOUS" = 1 ]; then
        if [ -e "$ACTIVE_PREVIOUS_ROOT/output" ]; then
            if [ -e "$ACTIVE_FINAL_OUT" ]; then
                rm -rf "$ACTIVE_FINAL_OUT" || RELEASE_FOLDER_ROLLBACK_OK=0
            fi
            if [ "$RELEASE_FOLDER_ROLLBACK_OK" = 1 ]; then
                mv "$ACTIVE_PREVIOUS_ROOT/output" "$ACTIVE_FINAL_OUT" || RELEASE_FOLDER_ROLLBACK_OK=0
            fi
        elif [ ! -e "$ACTIVE_FINAL_OUT" ]; then
            echo "Не найден ни текущий output, ни rollback в $ACTIVE_PREVIOUS_ROOT/output." >&2
            RELEASE_FOLDER_ROLLBACK_OK=0
        fi
    elif [ -e "$ACTIVE_FINAL_OUT" ]; then
        rm -rf "$ACTIVE_FINAL_OUT" || RELEASE_FOLDER_ROLLBACK_OK=0
    fi

    if [ "$RELEASE_FOLDER_ROLLBACK_OK" = 1 ]; then
        rm -rf "$ACTIVE_PREVIOUS_ROOT" || RELEASE_FOLDER_ROLLBACK_OK=0
    fi
    [ "$RELEASE_FOLDER_ROLLBACK_OK" = 1 ] || RELEASE_ROLLBACK_FAILED=1
    [ "$RELEASE_ROLLBACK_FAILED" = 0 ] || return 1

    ACTIVE_PUBLISH=0
    ACTIVE_PREVIOUS_ROOT=""
    ACTIVE_FINAL_OUT=""
    ACTIVE_HAD_PREVIOUS=0
    return 0
}

cleanup_release_stage() {
    preserve_zip_recovery=0
    if [ "$ACTIVE_PUBLISH" = 1 ]; then
        if ! rollback_active_release; then
            preserve_zip_recovery=1
            echo "Не удалось автоматически вернуть предыдущий release output: $ACTIVE_PREVIOUS_ROOT" >&2
        fi
    elif [ -n "$ACTIVE_PREVIOUS_ROOT" ] && [ -d "$ACTIVE_PREVIOUS_ROOT" ]; then
        rm -rf "$ACTIVE_PREVIOUS_ROOT" || true
    fi
    if [ -n "$STAGING_DIR" ] && [ -d "$STAGING_DIR" ]; then
        rm -rf "$STAGING_DIR" || true
    fi
    if [ "$preserve_zip_recovery" = 0 ] && [ -n "$ZIP_STAGE_DIR" ] && [ -d "$ZIP_STAGE_DIR" ]; then
        rm -rf "$ZIP_STAGE_DIR" || true
    elif [ "$preserve_zip_recovery" = 1 ] && [ -n "$ZIP_STAGE_DIR" ]; then
        echo "ZIP recovery сохранён в $ZIP_STAGE_DIR; не удаляйте его до ручного восстановления." >&2
    fi
    if [ "$RELEASE_LOCK_HELD" = 1 ] && [ -n "$RELEASE_LOCK" ]; then
        rmdir "$RELEASE_LOCK" 2>/dev/null || true
    fi
}

handle_release_signal() {
    trap - HUP INT TERM
    exit 1
}

trap cleanup_release_stage EXIT
trap handle_release_signal HUP INT TERM

mkdir -p "$BUILD"
RELEASE_LOCK="$BUILD/.release-$VERSION.lock"
# Блокируем обработчики только на tiny critical section mkdir+ownership flag: иначе signal между
# успешным mkdir и assignment оставит stale lock, а преждевременный cleanup мог бы тронуть чужой lock.
trap '' HUP INT TERM
if ! mkdir "$RELEASE_LOCK" 2>/dev/null; then
    trap handle_release_signal HUP INT TERM
    echo "Уже идёт сборка версии $VERSION (lock: $RELEASE_LOCK)." >&2
    exit 1
fi
RELEASE_LOCK_HELD=1
trap handle_release_signal HUP INT TERM

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

    if [ ! -s "$RELEASE_README" ]; then
        echo "Нет $RELEASE_README — пользовательская инструкция обязательна для релиза." >&2
        exit 1
    fi

    for helper in $COMMON_INSTALLER_FILES; do
        if [ ! -f "$COMMON_INSTALLER/$helper" ]; then
            echo "Нет обязательного helper-файла $COMMON_INSTALLER/$helper." >&2
            exit 1
        fi
    done

    # Hash намеренно продублирован в Unix host-helper и device-helper, которые работают вне build tree.
    # Windows BAT остаётся максимально простым; device-helper всё равно проверяет APK до установки.
    for helper in dns-overlay.sh dns-overlay-device.sh; do
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
    cp -p "$RELEASE_README" "$out/README.txt"
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
    command -v zip >/dev/null || {
        echo "zip не найден — без --no-zip нельзя согласованно обновить папку и архив." >&2
        return 1
    }
    mkdir -p "$DIST"
    ZIP_STAGE_DIR="$(mktemp -d "$DIST/.zip-stage.XXXXXX")" || return 1
    zip_tmp="$ZIP_STAGE_DIR/$name.zip"
    # -q тихо, -r рекурсивно; пакуем ИМЕНЕМ папки, поэтому идём в родителя.
    if ! (cd "$(dirname "$dir")" && zip -qr "$zip_tmp" "$(basename "$dir")"); then
        rm -rf "$ZIP_STAGE_DIR"
        ZIP_STAGE_DIR=""
        echo "Не удалось собрать архив $name.zip; предыдущий архив сохранён." >&2
        return 1
    fi
    if command -v unzip >/dev/null 2>&1 && ! unzip -tq "$zip_tmp" >/dev/null; then
        rm -rf "$ZIP_STAGE_DIR"
        ZIP_STAGE_DIR=""
        echo "Проверка нового архива $name.zip завершилась ошибкой; предыдущий архив сохранён." >&2
        return 1
    fi
    ACTIVE_ZIP_FINAL="$DIST/$name.zip"
    zip_had_previous=0
    if [ -e "$ACTIVE_ZIP_FINAL" ]; then
        zip_had_previous=1
        # Не уносим рабочий ZIP с финального пути: новый архив заменит его одним atomic rename.
        if ! cp -p "$ACTIVE_ZIP_FINAL" "$ZIP_STAGE_DIR/previous.zip"; then
            ACTIVE_ZIP_FINAL=""
            rm -rf "$ZIP_STAGE_DIR"
            ZIP_STAGE_DIR=""
            return 1
        fi
    fi
    ACTIVE_ZIP_HAD_PREVIOUS="$zip_had_previous"
    # Флаг включаем последним: signal до этой строки видит старый ZIP нетронутым.
    ACTIVE_ZIP_TRACKED=1
    if ! mv -f "$zip_tmp" "$ACTIVE_ZIP_FINAL"; then
        rollback_active_zip || true
        echo "Не удалось опубликовать архив $name.zip; предыдущий архив сохранён." >&2
        return 1
    fi
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
            {
                if (!sub(/\r$/, "")) exit 1
                if ($0 ~ /[^ -~\t]/) exit 1
            }
            END { if (NR == 0) exit 1 }
        ' "$batch"; then
            echo "Некорректный $batch — .bat должен использовать CRLF и только ASCII." >&2
            exit 1
        fi
    done
}

verify_release_payload() {
    out="$1"
    flavor="$2"
    required="README.txt native.apk restore_mode.apk $DNS_OVERLAY_NAME dns-overlay.sh dns-overlay.bat install-yandex-dns.bat dns-overlay-device.sh install.sh install.bat remove.sh remove.bat privapp-permissions-ru.big.town.anative.xml adb.exe AdbWinApi.dll AdbWinUsbApi.dll"
    if [ "$flavor" = full ]; then
        required="$required frida-inject-16.2.1-android-arm64 load.bin steeringwheelkeys.js launcherdock.js multidisplay.js vd_bypass.js apollo_tech.js init.logcat.original.sh voyahtune.load.rc voyahtune.load.sh"
    fi
    for payload in $required; do
        if [ ! -s "$out/$payload" ]; then
            echo "Релиз $flavor неполон: отсутствует или пуст $out/$payload." >&2
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
        if [ ! -s "$out/$f" ]; then
            echo "Нет $out/$f — с --no-build APK должны уже лежать в папке релиза." >&2
            exit 1
        fi
    done
}

# Каждый вариант собирается в новом каталоге. При --no-build из предыдущего output переносим только
# два APK; stale scripts, backup/ и любые посторонние файлы в staging попасть не могут.
prepare_release_dir() {
    final_out="$1"
    if [ "$DO_BUILD" != 1 ]; then
        require_apks "$final_out"
    fi
    mkdir -p "$BUILD"
    STAGING_DIR="$(mktemp -d "$BUILD/.release-stage.XXXXXX")" || exit 1
    STAGED_OUT="$STAGING_DIR/$(basename "$final_out")"
    mkdir "$STAGED_OUT"

    if [ "$DO_BUILD" != 1 ]; then
        for apk in native.apk restore_mode.apk; do
            cp -p "$final_out/$apk" "$STAGED_OUT/$apk"
            if ! cmp -s "$final_out/$apk" "$STAGED_OUT/$apk"; then
                echo "Копия $apk в clean staging не совпала с исходным APK." >&2
                exit 1
            fi
        done
    fi
}

publish_release_dir() {
    staged_out="$1"
    final_out="$2"
    previous_root="$(mktemp -d "$BUILD/.release-previous.XXXXXX")" || return 1
    publish_had_previous=0
    if [ -e "$final_out" ]; then
        publish_had_previous=1
    fi
    ACTIVE_PREVIOUS_ROOT="$previous_root"
    ACTIVE_FINAL_OUT="$final_out"
    ACTIVE_HAD_PREVIOUS="$publish_had_previous"
    # Флаг включаем последним: до него EXIT-cleanup не считает неизменённый final частью транзакции.
    ACTIVE_PUBLISH=1
    if [ "$publish_had_previous" = 1 ]; then
        if ! mv "$final_out" "$previous_root/output"; then
            ACTIVE_PUBLISH=0
            ACTIVE_PREVIOUS_ROOT=""
            ACTIVE_FINAL_OUT=""
            ACTIVE_HAD_PREVIOUS=0
            rm -rf "$previous_root"
            return 1
        fi
    fi

    if mv "$staged_out" "$final_out"; then
        rm -rf "$STAGING_DIR"
        STAGING_DIR=""
        STAGED_OUT=""
        return 0
    fi

    rollback_active_release || echo "Rollback output остался в $ACTIVE_PREVIOUS_ROOT/output." >&2
    return 1
}

commit_release_dir() {
    [ "$ACTIVE_PUBLISH" = 1 ] || return 1
    # Одна assignment — commit point всей пары output+ZIP; после неё signal оставляет новые артефакты.
    ACTIVE_PUBLISH=0
    rm -rf "$ACTIVE_PREVIOUS_ROOT"
    if [ -n "$ZIP_STAGE_DIR" ]; then
        rm -rf "$ZIP_STAGE_DIR"
    fi
    ACTIVE_PREVIOUS_ROOT=""
    ACTIVE_FINAL_OUT=""
    ACTIVE_HAD_PREVIOUS=0
    ZIP_STAGE_DIR=""
    ACTIVE_ZIP_TRACKED=0
    ACTIVE_ZIP_FINAL=""
    ACTIVE_ZIP_HAD_PREVIOUS=0
}

if [ "$DO_BUILD" != 1 ]; then
    [ "$DO_FULL" = 0 ] || require_apks "$BUILD/VoyahTune-$VERSION"
    [ "$DO_LIGHT" = 0 ] || require_apks "$BUILD/VoyahTune-$VERSION-light"
fi

# ---------------------------------------------------------------------------------------------
# FULL: полный набор — инжект-скрипты, boot-обвязка, frida, Windows-инструменты.
# ---------------------------------------------------------------------------------------------
if [ "$DO_FULL" = 1 ]; then
    OUT="$BUILD/VoyahTune-$VERSION"
    echo ""
    echo "########## FULL → Releases/build/VoyahTune-$VERSION ##########"
    prepare_release_dir "$OUT"
    STAGE="$STAGED_OUT"

    if [ "$DO_BUILD" = 1 ]; then build_apks full "$STAGE"; fi

    cp "$COMMON/tools/"*                                    "$STAGE/"
    cp "$COMMON/inject/"*.js                                "$STAGE/"
    cp "$COMMON/system/"*                                   "$STAGE/"
    copy_common_release_assets "$STAGE"
    for f in "$COMMON/installer/full/"*; do
        copy_stamped "$f" "$STAGE/$(basename "$f")"
    done
    verify_release_payload "$STAGE" full
    verify_windows_batch_files "$STAGE"
    publish_release_dir "$STAGE" "$OUT"
    make_zip "$OUT" "VoyahTune-$VERSION"
    commit_release_dir
    echo "FULL готов → $OUT"
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
    prepare_release_dir "$OUT"
    STAGE="$STAGED_OUT"

    if [ "$DO_BUILD" = 1 ]; then build_apks light "$STAGE"; fi

    for t in $LIGHT_TOOLS; do
        [ -f "$COMMON/tools/$t" ] || { echo "Нет $COMMON/tools/$t" >&2; exit 1; }
        cp "$COMMON/tools/$t" "$STAGE/"
    done
    cp "$COMMON/system/privapp-permissions-ru.big.town.anative.xml" "$STAGE/"
    copy_common_release_assets "$STAGE"
    for f in "$COMMON/installer/light/"*; do
        copy_stamped "$f" "$STAGE/$(basename "$f")"
    done
    verify_release_payload "$STAGE" light
    verify_windows_batch_files "$STAGE"
    publish_release_dir "$STAGE" "$OUT"
    make_zip "$OUT" "VoyahTune-$VERSION-light"
    commit_release_dir
    echo "LIGHT готов → $OUT"
fi

echo ""
echo "=== Состав релиза ==="
[ "$DO_FULL" = 1 ]  && ls -1 "$BUILD/VoyahTune-$VERSION"
[ "$DO_LIGHT" = 1 ] && { echo "--- light:"; ls -1 "$BUILD/VoyahTune-$VERSION-light"; }
echo ""
echo "Не забыть: описание версии в hownews.md."
