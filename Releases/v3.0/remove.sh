#!/bin/sh
# Удаление Open Voyah v3.0 и откат Frida-перехвата кнопок.
adb root
adb wait-for-device
adb shell mount -o rw,remount /

# Восстановить исходный load.bin: из backup/ если он есть, иначе штатный load.bin.bak
if [ -f backup/cunba_patch_load.bin ]; then
    echo "Restore load.bin из backup/"
    adb push backup/cunba_patch_load.bin /sdcard/Download/cunba/patch/load.bin
else
    echo "Restore load.bin из load.bin.bak"
    adb push load.bin.bak /sdcard/Download/cunba/patch/load.bin
fi
adb shell "rm -f /data/local/bin/keymng2.js"
adb shell "rm -f /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml"

# Вернуть чистый init.logcat.sh (только логирование, без setenforce/telnetd/цикла load.bin)
echo "Restore чистого /system/etc/init.logcat.sh"
adb push init.logcat.original.sh /system/etc/init.logcat.sh
adb shell "chmod 644 /system/etc/init.logcat.sh"

adb shell rm -r /system/priv-app/Native
adb shell "ls -all /system/priv-app/Native"
adb shell pm uninstall ru.big.town.restoremode
adb shell am force-stop ru.big.town.anative
adb reboot
