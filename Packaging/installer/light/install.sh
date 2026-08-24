#!/bin/sh
# Установка Open Voyah v@VERSION@-LIGHT. Запускать из папки релиза (бэкапы падают в ./backup).
# LIGHT = без Frida/root-инъекций: режимы вождения, прямой Binder Apollo, автосвет, прогрев/статусы, дворники,
# поездки, Power Hold, режим мойки, звук пешеходов, плавающая кнопка «Назад», установка приложений,
# ярлыки приложений (обычный запуск). БЕЗ сплита/дока/VirtualDisplay, БЕЗ Frida и любой root-инъекции,
# БЕЗ раздела «Кнопки на руле». init.logcat.sh системы НЕ трогаем.
if [ ! -f ./dns-overlay.sh ]; then
    echo "!!! Не найден ./dns-overlay.sh — установка прервана до изменения устройства."
    exit 1
fi
. ./dns-overlay.sh || {
    echo "!!! Не удалось загрузить ./dns-overlay.sh — установка прервана."
    exit 1
}
for ydns_required in ydns_prepare_helper ydns_query_state choose_yandex_dns install_yandex_dns disable_yandex_dns; do
    if ! command -v "$ydns_required" >/dev/null 2>&1; then
        echo "!!! dns-overlay.sh не содержит $ydns_required — установка прервана."
        exit 1
    fi
done
for LIGHT_REQUIRED_ASSET in native.apk restore_mode.apk \
        privapp-permissions-ru.big.town.anative.xml; do
    if [ ! -s "$LIGHT_REQUIRED_ASSET" ]; then
        echo "!!! Отсутствует или пуст обязательный файл $LIGHT_REQUIRED_ASSET — устройство не изменялось."
        exit 1
    fi
done
if ! ydns_prepare_helper; then
    echo "!!! Не удалось подготовить DNS-overlay helper — установка прервана."
    exit 1
fi

adb root
adb wait-for-device
adb root

# Android 11 keeps app CE/DE state behind PackageManager/installd and /data_mirror. Never repair
# these roots with mkdir/rm. After the reboot that scans the system APK, install-existing creates
# the user state; a -k cycle self-heals packages damaged by older removers without deleting prefs.
wait_for_android_boot() {
    adb wait-for-device || return 1
    BOOT_WAIT=0
    while [ "$BOOT_WAIT" -lt 60 ]; do
        [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
        sleep 5
        BOOT_WAIT=$((BOOT_WAIT + 1))
    done
    [ "$BOOT_WAIT" -lt 60 ] || return 1
    adb root >/dev/null 2>&1 || return 1
    adb wait-for-device || return 1
    adb root >/dev/null 2>&1 || return 1
}

native_user_data_ready() {
    [ "$(adb shell '
        if pm list packages --user 0 2>/dev/null | grep -qx "package:ru.big.town.anative" \
                && pm path ru.big.town.anative 2>/dev/null | grep -q "^package:" \
                && [ -d /data/user/0/ru.big.town.anative ] \
                && [ -d /data/user_de/0/ru.big.town.anative ]; then
            echo READY
        else
            echo BROKEN
        fi
    ' 2>/dev/null | tr -d '\r')" = "READY" ]
}

ensure_native_user_ready() {
    echo "=== Проверка PackageManager data Native (Android 11 CE+DE) ==="
    if ! native_user_data_ready; then
        echo "  Native не зарегистрирован полностью — восстанавливаем через installd."
        adb shell "pm uninstall -k --user 0 ru.big.town.anative >/dev/null 2>&1 || true" || return 1
        NATIVE_INSTALL_RESULT=$(adb shell \
            "cmd package install-existing --user 0 --wait ru.big.town.anative" 2>&1) || {
            echo "!!! install-existing Native завершился ошибкой: $NATIVE_INSTALL_RESULT"
            return 1
        }
        echo "  $NATIVE_INSTALL_RESULT"
    fi
    if ! native_user_data_ready; then
        echo "!!! Native APK найден, но PackageManager не создал оба CE/DE data-каталога."
        return 1
    fi
    adb shell "am broadcast -a com.qinggan.intent.QINGGAN_BOOT_COMPLETE -n ru.big.town.anative/.SetModesReceiverStatic >/dev/null" \
        || return 1
    NATIVE_START_WAIT=0
    while [ "$NATIVE_START_WAIT" -lt 20 ]; do
        [ -n "$(adb shell pidof ru.big.town.anative 2>/dev/null | tr -d '\r')" ] && {
            echo "  Native запущен; CE/DE и process attach подтверждены."
            return 0
        }
        sleep 1
        NATIVE_START_WAIT=$((NATIVE_START_WAIT + 1))
    done
    echo "!!! Native не запустился после восстановления package data; установка не подтверждена."
    return 1
}

# Light не содержит Frida: удаляем entitlement hook при переходе full -> light.
echo "=== Удаление Apollo VehicleSetting hook для light ==="
for APOLLO_SAFE_KEY in \
        open_voyah_apollo_legacy_hook_enabled \
        open_voyah_apollo_master \
        open_voyah_apollo_profile_supported \
        open_voyah_apollo_profile_heartbeat; do
    if ! adb shell settings put global "$APOLLO_SAFE_KEY" 0; then
        echo "!!! Не удалось записать $APOLLO_SAFE_KEY=0 — установка прервана до записи в /system."
        exit 1
    fi
    APOLLO_SAFE_STATE=$(adb shell settings get global "$APOLLO_SAFE_KEY" 2>/dev/null \
        | tr -d '\r')
    if [ "$APOLLO_SAFE_STATE" != "0" ]; then
        echo "!!! $APOLLO_SAFE_KEY не подтвердил 0 — установка прервана до записи в /system."
        exit 1
    fi
done
adb shell am force-stop com.qinggan.app.vehiclesetting 2>/dev/null
adb shell "rm -f /data/local/bin/apollo_tech.js /data/local/bin/apollo_tech.js.new /data/local/tmp/voyahtune_apollo.pid /data/local/tmp/voyahtune_apollo.attempt /data/local/tmp/voyahtune_apollo.txt /data/local/tmp/voyahtune_apollo.txt.try /data/local/tmp/voyah_apollo.pid /data/local/tmp/voyah_apollo.down /data/local/tmp/voyah_apollo.disabled /data/local/tmp/voyah_apollo.txt /data/local/tmp/voyah_apollo.txt.1 /data/local/tmp/voyah_apollo.txt.try" 2>/dev/null
for APOLLO_OLD_KEY in open_voyah_apollo_legacy_hook_enabled open_voyah_apollo_master \
        open_voyah_apollo_asc open_voyah_apollo_sdb open_voyah_apollo_profile_supported \
        open_voyah_apollo_profile_heartbeat; do
    adb shell settings delete global "$APOLLO_OLD_KEY" 2>/dev/null
done
echo "  Apollo entitlement agent и его маркеры удалены; light остаётся без Frida-активации."

# Оба флейвора Native владеют signature-разрешением прямой записи в CanBus. Чужой первый владелец
# сделал бы установленный APK несовместимым, поэтому конфликт проверяется до изменения /system.
echo "=== Preflight владельца com.qinggan.permission.WRITE_CANBUS ==="
CANBUS_PERMISSION_DUMP=$(adb shell dumpsys package permissions 2>/dev/null)
if [ $? -ne 0 ]; then
    echo "!!! PackageManager permissions недоступны — установка прервана до записи в /system."
    exit 1
fi
case "$CANBUS_PERMISSION_DUMP" in
    *"Permission [com.qinggan.permission.WRITE_CANBUS]"*)
        CANBUS_PERMISSION_OWNER=$(printf '%s\n' "$CANBUS_PERMISSION_DUMP" | awk '
            /Permission \[com\.qinggan\.permission\.WRITE_CANBUS\]/ { in_block=1; next }
            in_block && /Permission \[/ { exit }
            in_block && /sourcePackage=/ {
                sub(/^.*sourcePackage=/, ""); gsub(/[[:space:]]/, ""); print; exit
            }')
        if [ "$CANBUS_PERMISSION_OWNER" != "ru.big.town.anative" ]; then
            if [ -n "$CANBUS_PERMISSION_OWNER" ]; then
                echo "!!! com.qinggan.permission.WRITE_CANBUS уже принадлежит $CANBUS_PERMISSION_OWNER."
            else
                echo "!!! Владелец com.qinggan.permission.WRITE_CANBUS не определён однозначно."
            fi
            echo "    Удалите несовместимый пакет и повторите light install; /system ещё не изменялся."
            exit 1
        fi
        echo "  Permission уже принадлежит ru.big.town.anative — совместимое обновление."
        ;;
    *)
        echo "  Permission ещё не объявлен — его создаст Native."
        ;;
esac

# --- Гарантируем ЗАПИСЫВАЕМЫЙ /system --------------------------------------------------------
# Light тоже пишет в /system (priv-app + whitelist привилегий), поэтому подготовка нужна ровно та же,
# что и в full. Без неё на стоковой/после-OTA голове dm-verity держит /system read-only → push даёт
# "I/O error" и установка падает. disable-verity вступает в силу ТОЛЬКО после РЕБУТА. Делаем
# ИДЕМПОТЕНТНО: если /system уже записываем (готовая голова) — ребута НЕ будет; иначе снимаем verity,
# ОДИН раз перезагружаемся и продолжаем. adb remount = OverlayFS поверх read-only/динамических
# разделов (устойчивее сырого mount -o rw,remount). При невозможности — прерываемся, НЕ трогая /system.
system_is_writable() {
    adb remount >/dev/null 2>&1
    adb shell 'mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null' >/dev/null 2>&1
    [ "$(adb shell 'touch /system/.ovw_rwtest 2>/dev/null && rm -f /system/.ovw_rwtest && echo RW || echo RO' | tr -d '\r')" = "RW" ]
}

echo "=== Готовим /system к записи (verity → overlay) ==="
adb disable-verity 2>&1 | sed 's/^/  /'
if ! system_is_writable; then
    echo "  /system ещё read-only → перезагрузка ОДИН раз (применяем disable-verity)..."
    adb reboot
    adb wait-for-device
    i=0
    while [ $i -lt 60 ]; do
        [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
        sleep 5; i=$((i + 1))
    done
    sleep 3
    adb root >/dev/null 2>&1; adb wait-for-device; adb root >/dev/null 2>&1
fi
if ! system_is_writable; then
    echo "!!! /system ОСТАЁТСЯ read-only — установка прервана (в /system ничего не тронуто)."
    echo "    Причины: заблокирован загрузчик (disable-verity не срабатывает) / прошивка с EROFS"
    echo "    (несжимаемая read-only ФС) / verity не снимается на этой сборке."
    echo "    Проверьте вручную: adb disable-verity ; adb reboot ; adb root ; adb remount ; adb shell mount | grep system"
    exit 1
fi
echo "  /system записываем — продолжаем."

BACKUP_DIR="backup"
mkdir -p "$BACKUP_DIR"

# Бэкап файла с головы перед перезаписью. Если бэкап уже есть — не трогаем (сохраняем оригинал).
backup_pull() {
    if [ -f "$BACKUP_DIR/$2" ]; then
        echo "Backup: $BACKUP_DIR/$2 уже есть — пропуск (сохраняем оригинал)"
        return
    fi
    if adb pull "$1" "$BACKUP_DIR/$2" >/dev/null 2>&1; then
        echo "Backup: $1 -> $BACKUP_DIR/$2"
    else
        echo "Backup: $1 отсутствует, пропуск"
    fi
}

echo "=== Бэкап перезаписываемых файлов в $BACKUP_DIR/ ==="
backup_pull /system/priv-app/Native/Native.apk     Native.apk
backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml

# NB: install.sh — это ОБНОВЛЕНИЕ и СОХРАНЯЕТ настройки. RestoreMode ставится через -r (его data
# остаётся); Native обновляется пушем APK в /system БЕЗ сноса data, поэтому локальные тумблеры Native
# (autoLight / wiperCold / floatingBack) тоже сохраняются.
# Полная чистка протухшего состояния Native (лечение краха zygote "data_de/null") вынесена в remove.sh.
echo "=== Native.apk в /system/priv-app (нужны привилегированные пермишены для CAN-функций) ==="
# /system уже сделан записываемым выше (verity, overlay) — отдельный remount не нужен.
adb shell mkdir -p /system/priv-app/Native
adb shell chmod 755 /system/priv-app/Native
adb push native.apk /system/priv-app/Native/Native.apk
adb shell "ls -all /system/priv-app/Native"

# Whitelist привилегированных пермишенов (нужен на enforce-ROM: FORCE_STOP/WRITE_SECURE_SETTINGS/…)
adb shell "mkdir -p /system/etc/permissions"
adb push privapp-permissions-ru.big.town.anative.xml /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml
adb shell "chmod 644 /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml"

# Включить power hold (leave car), если опция выключена или отсутствует
LEAVECAR=$(adb shell getprop persist.app.feature.leavecar | tr -d '\r')
if [ "$LEAVECAR" != "true" ]; then
    echo "Enabling leave car (power hold)..."
    adb shell setprop persist.app.feature.leavecar true
fi

if ! adb install -r -g restore_mode.apk; then
    echo "!!! RestoreMode не установлен — исправьте ошибку и повторите installer до перезагрузки."
    exit 1
fi

echo "=== DNS для доступа через T-Box ==="
if ! YDNS_CURRENT="$(ydns_query_state)"; then
    echo "!!! Не удалось определить текущее состояние DNS-overlay — финальная перезагрузка отменена."
    exit 1
fi
case "$YDNS_CURRENT" in
    on|off|external|broken) ;;
    *)
        echo "!!! DNS-overlay helper вернул неизвестное состояние: $YDNS_CURRENT"
        exit 1
        ;;
esac
echo "Текущее состояние DNS-overlay: $YDNS_CURRENT"
YDNS_REQUEST=
if ! choose_yandex_dns "$YDNS_CURRENT"; then
    echo "!!! Не удалось получить выбор DNS-overlay — финальная перезагрузка отменена."
    exit 1
fi
case "${YDNS_REQUEST:-keep}" in
    on)
        install_yandex_dns || {
            echo "!!! Установка DNS-overlay завершилась ошибкой — финальная перезагрузка отменена."
            exit 1
        }
        ;;
    off)
        disable_yandex_dns || {
            echo "!!! Отключение DNS-overlay завершилось ошибкой — финальная перезагрузка отменена."
            exit 1
        }
        ;;
    keep)
        echo "DNS-overlay: оставляем текущее состояние без изменений."
        ;;
    *)
        echo "!!! Неизвестный выбор DNS-overlay: ${YDNS_REQUEST:-<пусто>}"
        exit 1
        ;;
esac

# Ребут нужен, чтобы менеджер пакетов перечитал privapp-whitelist для /system/priv-app.
adb reboot
if ! wait_for_android_boot; then
    echo "!!! ГУ не завершило загрузку после установки; проверьте ADB и повторите installer."
    exit 1
fi
if ! ensure_native_user_ready; then
    echo "!!! Установка файлов завершена, но Native lifecycle не восстановлен."
    exit 1
fi
echo "Установка завершена и проверена."
