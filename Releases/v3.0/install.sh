#!/bin/sh
# Установка Open Voyah v3.0. Запускать из папки релиза (bэкапы падают в ./backup).
adb root
adb wait-for-device
adb root

BACKUP_DIR="backup"
mkdir -p "$BACKUP_DIR"

# Бэкап файла с головы в backup/ перед перезаписью. Если бэкап УЖЕ есть — не трогаем
# (иначе при повторной установке затрём оригинал нашим же файлом). Если файла нет — пропуск.
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
backup_pull /sdcard/Download/cunba/patch/load.bin  cunba_patch_load.bin
backup_pull /data/local/bin/keymng2.js             keymng2.js
backup_pull /data/local/bin/frida-inject           frida-inject
backup_pull /system/etc/init.logcat.sh             init.logcat.sh
backup_pull /system/priv-app/Native/Native.apk     Native.apk

echo "=== Frida-инфраструктура перехвата кнопок на руле ==="
adb shell "mkdir -p /sdcard/Download/cunba/patch"
adb push load.bin   /sdcard/Download/cunba/patch/load.bin
adb push keymng2.js /data/local/bin/keymng2.js
adb push frida-inject-16.2.1-android-arm64 /data/local/bin/frida-inject
adb shell "chmod 755 /data/local/bin/frida-inject /data/local/bin/keymng2.js"

echo "=== Native.apk в /system/priv-app ==="
adb shell mount -o rw,remount /
adb shell mkdir -p /system/priv-app/Native
adb shell chmod 755 /system/priv-app/Native
adb push native.apk /system/priv-app/Native/Native.apk
adb shell "ls -all /system/priv-app/Native"

# Whitelist привилегированных пермишенов (нужен на enforce-ROM: FORCE_STOP/WRITE_SECURE_SETTINGS/…)
backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml
adb shell "mkdir -p /system/etc/permissions"
adb push privapp-permissions-ru.big.town.anative.xml /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml
adb shell "chmod 644 /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml"

# Включить power hold (leave car), если опция выключена или отсутствует
LEAVECAR=$(adb shell getprop persist.app.feature.leavecar | tr -d '\r')
if [ "$LEAVECAR" != "true" ]; then
    echo "Enabling leave car (power hold)..."
    adb shell setprop persist.app.feature.leavecar true
fi

# Freeform для «Разделения экрана» (применяется после ребута)
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1

adb install -r -g restore_mode.apk
adb reboot
