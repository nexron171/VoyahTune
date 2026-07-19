@echo off
REM Удаление Open Voyah v3.0 и откат Frida-перехвата кнопок.
adb.exe root
adb.exe wait-for-device
adb.exe shell mount -o rw,remount /

REM Восстановить исходный load.bin: из backup\ если есть, иначе штатный load.bin.bak
if exist "backup\cunba_patch_load.bin" (
    echo Restore load.bin из backup\
    adb.exe push backup\cunba_patch_load.bin /sdcard/Download/cunba/patch/load.bin
) else (
    echo Restore load.bin из load.bin.bak
    adb.exe push load.bin.bak /sdcard/Download/cunba/patch/load.bin
)
adb.exe shell "rm -f /data/local/bin/keymng2.js"
adb.exe shell "rm -f /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml"

REM Вернуть чистый init.logcat.sh (только логирование, без setenforce/telnetd/цикла load.bin)
echo Restore чистого /system/etc/init.logcat.sh
adb.exe push init.logcat.original.sh /system/etc/init.logcat.sh
adb.exe shell "chmod 644 /system/etc/init.logcat.sh"

adb.exe shell rm -r /system/priv-app/Native
adb.exe shell "ls -all /system/priv-app/Native"
adb.exe shell pm uninstall ru.big.town.restoremode
adb.exe shell am force-stop ru.big.town.anative
adb.exe reboot
echo "Press any key..."
pause
