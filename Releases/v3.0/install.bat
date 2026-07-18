@echo off
REM Установка Open Voyah v3.0. Запускать из папки релиза (бэкапы падают в .\backup).
adb.exe root
adb.exe wait-for-device
adb.exe root

set BACKUP_DIR=backup
if not exist "%BACKUP_DIR%" mkdir "%BACKUP_DIR%"

echo === Бэкап перезаписываемых файлов в %BACKUP_DIR%\ ===
call :backup_pull /sdcard/Download/cunba/patch/load.bin  cunba_patch_load.bin
call :backup_pull /data/local/bin/keymng2.js             keymng2.js
call :backup_pull /data/local/bin/frida-inject           frida-inject
call :backup_pull /system/etc/init.logcat.sh             init.logcat.sh
call :backup_pull /system/priv-app/Native/Native.apk     Native.apk

echo === Frida-инфраструктура перехвата кнопок на руле ===
adb.exe shell "mkdir -p /sdcard/Download/cunba/patch"
adb.exe push load.bin   /sdcard/Download/cunba/patch/load.bin
adb.exe push keymng2.js /data/local/bin/keymng2.js
adb.exe push frida-inject-16.2.1-android-arm64 /data/local/bin/frida-inject
adb.exe shell "chmod 755 /data/local/bin/frida-inject /data/local/bin/keymng2.js"

echo === Native.apk в /system/priv-app ===
adb.exe shell mount -o rw,remount /
adb.exe shell mkdir -p /system/priv-app/Native
adb.exe shell chmod 755 /system/priv-app/Native
adb.exe push native.apk /system/priv-app/Native/Native.apk
adb.exe shell "ls -all /system/priv-app/Native"

REM Whitelist привилегированных пермишенов (нужен на enforce-ROM)
call :backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml
adb.exe shell "mkdir -p /system/etc/permissions"
adb.exe push privapp-permissions-ru.big.town.anative.xml /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml
adb.exe shell "chmod 644 /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml"

REM Включить power hold (leave car), если опция выключена или отсутствует
set LEAVECAR=
for /f "delims=" %%i in ('adb.exe shell getprop persist.app.feature.leavecar') do set LEAVECAR=%%i
if not "%LEAVECAR%"=="true" (
    echo Enabling leave car ^(power hold^)...
    adb.exe shell setprop persist.app.feature.leavecar true
)

REM Freeform для «Разделения экрана» (применяется после ребута)
adb.exe shell settings put global enable_freeform_support 1
adb.exe shell settings put global force_resizable_activities 1

adb.exe install -r -g restore_mode.apk
adb.exe reboot
echo "Press any key..."
pause
goto :eof

REM Бэкап файла с головы в backup\ перед перезаписью. Если бэкап уже есть — не трогаем
REM (иначе при повторной установке затрём оригинал). Если файла нет — пропуск.
:backup_pull
if exist "%BACKUP_DIR%\%~2" (
    echo Backup: %BACKUP_DIR%\%~2 уже есть - пропуск ^(сохраняем оригинал^)
    exit /b 0
)
adb.exe pull %1 "%BACKUP_DIR%\%~2" 1>nul 2>nul
if errorlevel 1 (
    echo Backup: %1 отсутствует, пропуск
) else (
    echo Backup: %1 -^> %BACKUP_DIR%\%~2
)
exit /b 0
