@echo off
chcp 65001 >nul
cd /d "%~dp0" || exit /b 1
REM Удаление Open Voyah v@VERSION@-LIGHT — полный откат к состоянию ДО установки.
REM Полный аналог remove.sh для Windows.
REM LIGHT ничего не инжектит и не трогает init.logcat.sh/Frida — чистим только priv-app + whitelist + оба APK.
set "YDNS_HELPER=%~dp0dns-overlay.bat"
if not exist "%YDNS_HELPER%" (
    echo !!! Не найден dns-overlay.bat - удаление прервано до изменения устройства.
    exit /b 1
)
call "%YDNS_HELPER%" prepare
if errorlevel 1 (
    echo !!! DNS-overlay helper не готов - удаление прервано до изменения устройства.
    exit /b 1
)

adb.exe root
adb.exe wait-for-device
adb.exe root
REM /system записываемым: снимаем verity (идемпотентно) + overlay-remount + сырой remount. Полный
REM ребут-цикл здесь не нужен (после install verity уже снята; если её вернул OTA - сначала install.bat).
adb.exe disable-verity >nul 2>nul
adb.exe remount >nul 2>nul
adb.exe shell "mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null"

echo === Откат DNS-overlay ===
call "%YDNS_HELPER%" restore
if errorlevel 1 (
    echo !!! Не удалось восстановить/отключить DNS-overlay - остальные компоненты не удалялись.
    exit /b 1
)

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
if errorlevel 1 (
    echo !!! Откат подготовлен, но ADB не смог перезагрузить ГУ. Выполните reboot вручную.
    pause
    exit /b 1
)
echo "Press any key..."
pause
exit /b 0
