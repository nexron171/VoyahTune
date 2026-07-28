@echo off
REM Удаление Open Voyah v@VERSION@-LIGHT — полный откат к состоянию ДО установки.
REM Полный аналог remove.sh для Windows.
REM LIGHT ничего не инжектит и не трогает init.logcat.sh/Frida — чистим только priv-app + whitelist + оба APK.
adb.exe root
adb.exe wait-for-device
adb.exe root
REM /system записываемым: снимаем verity (идемпотентно) + overlay-remount + сырой remount. Полный
REM ребут-цикл здесь не нужен (после install verity уже снята; если её вернул OTA - сначала install.bat).
adb.exe disable-verity >nul 2>nul
adb.exe remount >nul 2>nul
adb.exe shell "mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null"

REM --- Whitelist + Native из /system/priv-app (+ снять /data-оверлей обновления) ---
adb.exe shell "rm -f /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml"
adb.exe shell "rm -rf /system/priv-app/Native"
adb.exe shell "ls -all /system/priv-app/Native"
adb.exe shell pm uninstall ru.big.town.anative
adb.exe shell pm uninstall ru.big.town.restoremode
adb.exe shell am force-stop ru.big.town.anative
REM Полностью вычистить data-каталоги Native: pm uninstall системного priv-app не всегда их удаляет,
REM а протухшие данные ломают СЛЕДУЮЩУЮ установку (краш zygote при монтировании data_de/null/…).
adb.exe shell "rm -rf /data/user/0/ru.big.town.anative /data/user_de/0/ru.big.town.anative /data/data/ru.big.town.anative"

REM Примечание: persist.app.feature.leavecar (power hold) НЕ откатываем — это штатная функция авто.

adb.exe reboot
echo "Press any key..."
pause
