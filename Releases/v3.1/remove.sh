#!/bin/sh
# Удаление Open Voyah v3.1 — полный откат к состоянию ДО установки нашего приложения.
adb root
adb wait-for-device
adb shell mount -o rw,remount /

# --- Boot-хук: вернуть исходный init.logcat.sh (из backup если есть, иначе чистый оригинал) ---
if [ -f backup/init.logcat.sh ]; then
    echo "Restore init.logcat.sh из backup/"
    adb push backup/init.logcat.sh /system/etc/init.logcat.sh
else
    echo "Restore чистого init.logcat.original.sh"
    adb push init.logcat.original.sh /system/etc/init.logcat.sh
fi
adb shell "chmod 644 /system/etc/init.logcat.sh"

# --- Остановить наши живые Frida-хуки и load.bin (до ребута) ---
adb shell "pkill -f /data/local/bin/load.bin" 2>/dev/null
adb shell "ps -ef | grep frida-inject | grep -E 'vd_bypass|keymng2' | grep -v grep | awk '{print \$2}' | xargs kill -9" 2>/dev/null

# --- Убрать наши Frida-файлы (или вернуть бэкап, если что-то было до нас) ---
if [ -f backup/load.bin ]; then adb push backup/load.bin /data/local/bin/load.bin; else adb shell "rm -f /data/local/bin/load.bin"; fi
adb shell "rm -f /data/local/bin/vd_bypass.js"
if [ -f backup/keymng2.js ]; then adb push backup/keymng2.js /data/local/bin/keymng2.js; else adb shell "rm -f /data/local/bin/keymng2.js"; fi
if [ -f backup/frida-inject ]; then adb push backup/frida-inject /data/local/bin/frida-inject; else adb shell "rm -f /data/local/bin/frida-inject"; fi

# --- Whitelist + Native из /system/priv-app (+ снять /data-оверлей обновления) ---
adb shell "rm -f /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml"
adb shell "rm -rf /system/priv-app/Native"
adb shell "ls -all /system/priv-app/Native"
adb shell pm uninstall ru.big.town.anative
adb shell pm uninstall ru.big.town.restoremode
adb shell am force-stop ru.big.town.anative

# --- Откат наших global settings (freeform для VirtualDisplay-сплита) ---
adb shell settings delete global enable_freeform_support
adb shell settings delete global force_resizable_activities
# Примечание: persist.app.feature.leavecar (power hold) НЕ откатываем — это штатная функция авто.

adb reboot
