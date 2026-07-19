#!/bin/sh
# Установка Open Voyah v3.1. Запускать из папки релиза (бэкапы падают в ./backup).
# Ставит: Native (priv-app) + RestoreMode, whitelist привилегий, freeform, и Frida-обвязку —
#   1) кнопка-звёздочка (keymng2.js в keymanager),
#   2) VirtualDisplay-сплит (vd_bypass.js в system_server: обход ADD_TRUSTED_DISPLAY/INJECT_EVENTS).
# Boot-хук = наш /system/etc/init.logcat.sh, который крутит load.bin (watchdog обеих инъекций).
adb root
adb wait-for-device
adb root

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
backup_pull /data/local/bin/keymng2.js             keymng2.js
backup_pull /data/local/bin/vd_bypass.js           vd_bypass.js
backup_pull /data/local/bin/frida-inject           frida-inject
backup_pull /system/etc/init.logcat.sh             init.logcat.sh
backup_pull /system/priv-app/Native/Native.apk     Native.apk
backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml

# ВАЖНО: всё в /data/local/bin — оно доступно рано при загрузке (когда выполняется init.logcat.sh).
# /sdcard монтируется позже, поэтому load.bin ТАМ держать нельзя (не запустится на буте).
echo "=== Frida-инфраструктура (кнопка на руле + VirtualDisplay-сплит) ==="
adb shell "mkdir -p /data/local/bin"
adb push load.bin    /data/local/bin/load.bin
adb push keymng2.js  /data/local/bin/keymng2.js
adb push vd_bypass.js /data/local/bin/vd_bypass.js
adb push frida-inject-16.2.1-android-arm64 /data/local/bin/frida-inject
adb shell "chmod 755 /data/local/bin/frida-inject /data/local/bin/load.bin"
adb shell "chmod 644 /data/local/bin/keymng2.js /data/local/bin/vd_bypass.js"

echo "=== Boot-хук: наш init.logcat.sh (setenforce 0 + запуск load.bin) ==="
adb shell mount -o rw,remount /
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
