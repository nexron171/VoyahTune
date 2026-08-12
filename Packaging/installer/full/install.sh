#!/bin/sh
# Установка Open Voyah v@VERSION@. Запускать из папки релиза (бэкапы падают в ./backup).
# Ставит: Native (priv-app) + RestoreMode, whitelist привилегий, freeform, и Frida-обвязку —
#   1) кнопки руля (steeringwheelkeys.js в keymanager: звёздочка 3090 и DVR 173, один onKeyEvent),
#   2) VirtualDisplay-сплит (vd_bypass.js в system_server: обход ADD_TRUSTED_DISPLAY/INJECT_EVENTS),
#   3) Apollo/ADAS master-gate (apollo_tech.js в com.qinggan.app.vehiclesetting).
# Boot-хук = свои RC-сервисы /system/etc/init/voyahtune.*.rc (setenforce 0 + load.bin watchdog);
# штатный /system/etc/init.logcat.sh больше не трогаем.
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
if ! ydns_prepare_helper; then
    echo "!!! Не удалось подготовить DNS-overlay helper — установка прервана."
    exit 1
fi

adb root
adb wait-for-device
adb root

# Update не наследует stale ON: до disable-verity и любых /system mutations принудительно переводим
# Apollo master в безопасный 0 и проверяем фактически сохранённое значение.
echo "=== Preflight безопасного состояния Apollo master ==="
if ! adb shell settings put global open_voyah_apollo_master 0; then
    echo "!!! Не удалось выключить Apollo master — установка прервана до записи в /system."
    exit 1
fi
APOLLO_INSTALL_MASTER_STATE=$(adb shell settings get global open_voyah_apollo_master 2>/dev/null \
    | tr -d '\r')
if [ "$APOLLO_INSTALL_MASTER_STATE" != "0" ]; then
    echo "!!! Apollo master не подтвердил состояние 0 — установка прервана до записи в /system."
    exit 1
fi
echo "  Apollo master=0 подтверждён."

# Native full self-owns the signature permission needed by its fail-closed CAN writer. Android keeps
# the first installed declaration: an old VoyahTweaks owner would silently make our Native incompatible.
# Check before disable-verity/remount/touch, so a conflict leaves /system unchanged.
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
            echo "    Удалите несовместимый пакет и повторите full install; /system ещё не изменялся."
            exit 1
        fi
        echo "  Permission уже принадлежит ru.big.town.anative — совместимое обновление."
        ;;
    *)
        echo "  Permission ещё не объявлен — его создаст full Native."
        ;;
esac

# --- Гарантируем ЗАПИСЫВАЕМЫЙ /system --------------------------------------------------------
# Без этого на стоковой/после-OTA голове dm-verity держит /system read-only → push в /system даёт
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

# Одноразовый симметричный backup для нового файла: запоминаем и исходное отсутствие. Без .absent
# повторная установка приняла бы нашу предыдущую версию за заводской «оригинал».
backup_pull_with_absent() {
    if [ -f "$BACKUP_DIR/$2" ] || [ -f "$BACKUP_DIR/$2.absent" ]; then
        echo "Backup: исходное состояние $2 уже сохранено — пропуск"
        return
    fi
    REMOTE_STATE=$(adb shell "if [ -e '$1' ]; then echo PRESENT; else echo ABSENT; fi" 2>/dev/null | tr -d '\r')
    case "$REMOTE_STATE" in
        PRESENT)
            rm -f "$BACKUP_DIR/$2.new"
            if adb pull "$1" "$BACKUP_DIR/$2.new" >/dev/null 2>&1 \
                    && mv -f "$BACKUP_DIR/$2.new" "$BACKUP_DIR/$2"; then
                echo "Backup: $1 -> $BACKUP_DIR/$2"
                return 0
            fi
            rm -f "$BACKUP_DIR/$2.new"
            echo "!!! Не удалось сохранить существующий $1"
            return 1
            ;;
        ABSENT)
            if : > "$BACKUP_DIR/$2.absent"; then
                echo "Backup: $1 изначально отсутствует -> $BACKUP_DIR/$2.absent"
                return 0
            fi
            echo "!!! Не удалось создать $BACKUP_DIR/$2.absent"
            return 1
            ;;
        *)
            echo "!!! Не удалось определить исходное состояние $1"
            return 1
            ;;
    esac
}

echo "=== Бэкап перезаписываемых файлов в $BACKUP_DIR/ ==="
backup_pull /data/local/bin/load.bin               load.bin
backup_pull /data/local/bin/steeringwheelkeys.js   steeringwheelkeys.js
backup_pull /data/local/bin/launcherdock.js        launcherdock.js
backup_pull /data/local/bin/multidisplay.js        multidisplay.js
backup_pull /data/local/bin/vd_bypass.js           vd_bypass.js
if ! backup_pull_with_absent /data/local/bin/apollo_tech.js apollo_tech.js; then
    echo "!!! Apollo backup не создан — установка прервана до перезаписи файла."
    exit 1
fi
backup_pull /data/local/bin/frida-inject           frida-inject
backup_pull /system/priv-app/Native/Native.apk     Native.apk
backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml

# ВАЖНО: всё в /data/local/bin — оно доступно рано при загрузке (когда выполняется init.logcat.sh).
# /sdcard монтируется позже, поэтому load.bin ТАМ держать нельзя (не запустится на буте).
echo "=== Frida-инфраструктура (руль + VirtualDisplay + Apollo/ADAS) ==="
adb shell "mkdir -p /data/local/bin"
adb push load.bin              /data/local/bin/load.bin
adb push steeringwheelkeys.js  /data/local/bin/steeringwheelkeys.js
adb push launcherdock.js       /data/local/bin/launcherdock.js
adb push multidisplay.js       /data/local/bin/multidisplay.js
adb push vd_bypass.js          /data/local/bin/vd_bypass.js
# Не даём живому watchdog увидеть частично переданный safety-sensitive hook.
if ! adb push apollo_tech.js /data/local/bin/apollo_tech.js.new; then
    echo "!!! Не удалось передать apollo_tech.js — установка прервана."
    exit 1
fi
if ! adb shell "chmod 644 /data/local/bin/apollo_tech.js.new && mv -f /data/local/bin/apollo_tech.js.new /data/local/bin/apollo_tech.js"; then
    adb shell "rm -f /data/local/bin/apollo_tech.js.new"
    echo "!!! Не удалось атомарно установить apollo_tech.js — установка прервана."
    exit 1
fi
adb push frida-inject-16.2.1-android-arm64 /data/local/bin/frida-inject
adb shell "chmod 755 /data/local/bin/frida-inject /data/local/bin/load.bin"
adb shell "chmod 644 /data/local/bin/steeringwheelkeys.js /data/local/bin/launcherdock.js /data/local/bin/vd_bypass.js /data/local/bin/multidisplay.js /data/local/bin/apollo_tech.js"

echo "=== Boot-хук: свои RC-сервисы (setenforce 0 + запуск load.bin), init.logcat.sh не трогаем ==="
# /system уже сделан записываемым выше (verity → overlay); отдельный remount не нужен.
adb shell "mkdir -p /system/etc/init"
adb push voyahtune.setenforce.rc /system/etc/init/voyahtune.setenforce.rc
adb push voyahtune.load.rc       /system/etc/init/voyahtune.load.rc
adb push voyahtune.load.sh       /system/etc/init/voyahtune.load.sh
adb shell "chmod 644 /system/etc/init/voyahtune.setenforce.rc /system/etc/init/voyahtune.load.rc"
adb shell "chmod 755 /system/etc/init/voyahtune.load.sh"

# NB: install.sh — это ОБНОВЛЕНИЕ и СОХРАНЯЕТ настройки. RestoreMode ставится через -r (его data
# остаётся); Native обновляется пушем APK в /system БЕЗ сноса data, поэтому локальные тумблеры Native
# (autoLight / wiperCold / floatingBack, читаются из его prefs) тоже сохраняются.
# Полная чистка протухшего состояния Native (лечение краха zygote "data_de/null") вынесена в remove.sh.
# Порядок для чистого baseline (после порчи от старого remove): remove.sh → install.sh.
echo "=== Native.apk в /system/priv-app ==="
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

# Freeform (окна resizable — нужно для VirtualDisplay-сплита; применяется после ребута)
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1

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

adb reboot
