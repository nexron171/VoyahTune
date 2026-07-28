#!/bin/sh
# Установка Open Voyah v@VERSION@. Запускать из папки релиза (бэкапы падают в ./backup).
# Ставит: Native (priv-app) + RestoreMode, whitelist привилегий, freeform, и Frida-обвязку —
#   1) кнопки руля (steeringwheelkeys.js в keymanager: звёздочка 3090 и DVR 173, один onKeyEvent),
#   2) VirtualDisplay-сплит (vd_bypass.js в system_server: обход ADD_TRUSTED_DISPLAY/INJECT_EVENTS).
# Boot-хук = наш /system/etc/init.logcat.sh, который крутит load.bin (watchdog инъекций).
adb root
adb wait-for-device
adb root

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

echo "=== Бэкап перезаписываемых файлов в $BACKUP_DIR/ ==="
backup_pull /data/local/bin/load.bin               load.bin
backup_pull /data/local/bin/steeringwheelkeys.js   steeringwheelkeys.js
backup_pull /data/local/bin/launcherdock.js        launcherdock.js
backup_pull /data/local/bin/vd_bypass.js           vd_bypass.js
backup_pull /data/local/bin/frida-inject           frida-inject
backup_pull /system/etc/init.logcat.sh             init.logcat.sh
backup_pull /system/priv-app/Native/Native.apk     Native.apk
backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml

# ВАЖНО: всё в /data/local/bin — оно доступно рано при загрузке (когда выполняется init.logcat.sh).
# /sdcard монтируется позже, поэтому load.bin ТАМ держать нельзя (не запустится на буте).
echo "=== Frida-инфраструктура (кнопка на руле + VirtualDisplay-сплит) ==="
adb shell "mkdir -p /data/local/bin"
adb push load.bin              /data/local/bin/load.bin
adb push steeringwheelkeys.js  /data/local/bin/steeringwheelkeys.js
adb push launcherdock.js       /data/local/bin/launcherdock.js
adb push vd_bypass.js          /data/local/bin/vd_bypass.js
adb push frida-inject-16.2.1-android-arm64 /data/local/bin/frida-inject
adb shell "chmod 755 /data/local/bin/frida-inject /data/local/bin/load.bin"
adb shell "chmod 644 /data/local/bin/steeringwheelkeys.js /data/local/bin/launcherdock.js /data/local/bin/vd_bypass.js"

echo "=== Boot-хук: наш init.logcat.sh (setenforce 0 + запуск load.bin) ==="
# /system уже сделан записываемым выше (verity → overlay); отдельный remount не нужен.
adb push init.logcat.sh /system/etc/init.logcat.sh
adb shell "chmod 644 /system/etc/init.logcat.sh"

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

adb install -r -g restore_mode.apk
adb reboot
