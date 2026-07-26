#!/bin/sh
# Установка Open Voyah v3.2-LIGHT. Запускать из папки релиза (бэкапы падают в ./backup).
# LIGHT = только не-root функциональность: режимы вождения, автосвет, прогрев/статусы, дворники,
# поездки, Power Hold, режим мойки, звук пешеходов, плавающая кнопка «Назад», установка приложений,
# ярлыки приложений (обычный запуск). БЕЗ сплита/дока/VirtualDisplay, БЕЗ Frida и любой root-инъекции,
# БЕЗ раздела «Кнопки на руле». init.logcat.sh системы НЕ трогаем.
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
backup_pull /system/priv-app/Native/Native.apk     Native.apk
backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml

# NB: install.sh — это ОБНОВЛЕНИЕ и СОХРАНЯЕТ настройки. RestoreMode ставится через -r (его data
# остаётся); Native обновляется пушем APK в /system БЕЗ сноса data, поэтому локальные тумблеры Native
# (autoLight / wiperCold / floatingBack) тоже сохраняются.
# Полная чистка протухшего состояния Native (лечение краха zygote "data_de/null") вынесена в remove.sh.
echo "=== Native.apk в /system/priv-app (нужны привилегированные пермишены для CAN-функций) ==="
adb shell mount -o rw,remount /
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

adb install -r -g restore_mode.apk
# Ребут нужен, чтобы менеджер пакетов перечитал privapp-whitelist для /system/priv-app.
adb reboot
