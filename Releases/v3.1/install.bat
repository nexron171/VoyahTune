@echo off
REM Установка Open Voyah v3.1. Запускать из папки релиза (бэкапы падают в .\backup).
REM Ставит: Native (priv-app) + RestoreMode, whitelist привилегий, freeform, и Frida-обвязку —
REM   1) кнопки руля (steeringwheelkeys.js в keymanager: звёздочка 3090 и DVR 173, один onKeyEvent),
REM   2) VirtualDisplay-сплит (vd_bypass.js в system_server: обход ADD_TRUSTED_DISPLAY/INJECT_EVENTS).
REM Boot-хук = наш /system/etc/init.logcat.sh, который крутит load.bin (watchdog инъекций).
adb.exe root
adb.exe wait-for-device
adb.exe root

REM --- Гарантируем ЗАПИСЫВАЕМЫЙ /system ------------------------------------------------------
REM Без этого на стоковой/после-OTA голове dm-verity держит /system read-only -> push в /system
REM даёт "I/O error" и установка падает. disable-verity применяется ТОЛЬКО после РЕБУТА. Идемпотентно:
REM если /system уже записываем (готовая голова) - ребута НЕ будет; иначе снимаем verity, ОДИН раз
REM перезагружаемся и продолжаем. adb remount = OverlayFS (устойчивее сырого mount -o rw,remount).
echo === Готовим /system к записи (verity, overlay) ===
adb.exe disable-verity
call :ensure_rw
if "%RWSTATE%"=="RW" goto :sys_rw_ok
echo   /system ещё read-only - перезагрузка ОДИН раз (применяем disable-verity)...
adb.exe reboot
adb.exe wait-for-device
set /a _bi=0
:wait_boot_ovw
adb.exe shell getprop sys.boot_completed 2>nul | findstr /b "1" >nul
if not errorlevel 1 goto :booted_ovw
set /a _bi+=1
if %_bi% GEQ 60 goto :booted_ovw
timeout /t 5 >nul
goto :wait_boot_ovw
:booted_ovw
timeout /t 3 >nul
adb.exe root
adb.exe wait-for-device
adb.exe root
call :ensure_rw
if "%RWSTATE%"=="RW" goto :sys_rw_ok
echo !!! /system ОСТАЁТСЯ read-only - установка прервана (в /system ничего не тронуто).
echo     Причины: заблокирован загрузчик / прошивка EROFS / verity не снимается на этой сборке.
echo     Проверьте вручную: adb disable-verity, затем adb reboot, adb root, adb remount.
pause
exit /b 1
:sys_rw_ok
echo   /system записываем - продолжаем.

set BACKUP_DIR=backup
if not exist "%BACKUP_DIR%" mkdir "%BACKUP_DIR%"

echo === Бэкап перезаписываемых файлов в %BACKUP_DIR%\ ===
call :backup_pull /data/local/bin/load.bin               load.bin
call :backup_pull /data/local/bin/steeringwheelkeys.js   steeringwheelkeys.js
call :backup_pull /data/local/bin/vd_bypass.js           vd_bypass.js
call :backup_pull /data/local/bin/frida-inject           frida-inject
call :backup_pull /system/etc/init.logcat.sh             init.logcat.sh
call :backup_pull /system/priv-app/Native/Native.apk     Native.apk
call :backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml

REM ВАЖНО: всё в /data/local/bin — доступно рано при загрузке (когда выполняется init.logcat.sh).
REM /sdcard монтируется позже, поэтому load.bin ТАМ держать нельзя (не запустится на буте).
echo === Frida-инфраструктура (кнопки руля + VirtualDisplay-сплит) ===
adb.exe shell "mkdir -p /data/local/bin"
adb.exe push load.bin              /data/local/bin/load.bin
adb.exe push steeringwheelkeys.js  /data/local/bin/steeringwheelkeys.js
adb.exe push vd_bypass.js          /data/local/bin/vd_bypass.js
adb.exe push frida-inject-16.2.1-android-arm64 /data/local/bin/frida-inject
adb.exe shell "chmod 755 /data/local/bin/frida-inject /data/local/bin/load.bin"
adb.exe shell "chmod 644 /data/local/bin/steeringwheelkeys.js /data/local/bin/vd_bypass.js"

echo === Boot-хук: наш init.logcat.sh (setenforce 0 + запуск load.bin) ===
REM /system уже сделан записываемым выше (verity, overlay) - отдельный remount не нужен.
adb.exe push init.logcat.sh /system/etc/init.logcat.sh
adb.exe shell "chmod 644 /system/etc/init.logcat.sh"

REM NB: install — это ОБНОВЛЕНИЕ и СОХРАНЯЕТ настройки (RestoreMode через -r, Native пушем APK
REM в /system без сноса data). Полная чистка протухшего состояния Native вынесена в remove.bat.
REM Порядок для чистого baseline (после порчи от старого remove): remove.bat -> install.bat.
echo === Native.apk в /system/priv-app ===
adb.exe shell mkdir -p /system/priv-app/Native
adb.exe shell chmod 755 /system/priv-app/Native
adb.exe push native.apk /system/priv-app/Native/Native.apk
adb.exe shell "ls -all /system/priv-app/Native"

REM Whitelist привилегированных пермишенов (нужен на enforce-ROM: FORCE_STOP/WRITE_SECURE_SETTINGS/…)
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

REM Freeform (окна resizable — нужно для VirtualDisplay-сплита; применяется после ребута)
adb.exe shell settings put global enable_freeform_support 1
adb.exe shell settings put global force_resizable_activities 1

adb.exe install -r -g restore_mode.apk
adb.exe reboot
echo "Press any key..."
pause
goto :eof

REM Гарантирует rw на /system: adb remount (overlay) + сырой remount, затем ПРОБНЫЙ push в /system.
REM RWSTATE=RW если пробная запись прошла (система записываема), иначе RO. Пробный файл сразу удаляем.
:ensure_rw
adb.exe remount >nul 2>nul
adb.exe shell "mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null" >nul 2>nul
echo rwtest> "%TEMP%\_ovw_rwtest.tmp"
adb.exe push "%TEMP%\_ovw_rwtest.tmp" /system/_ovw_rwtest >nul 2>nul
if errorlevel 1 (set RWSTATE=RO) else (set RWSTATE=RW)
adb.exe shell "rm -f /system/_ovw_rwtest" >nul 2>nul
del "%TEMP%\_ovw_rwtest.tmp" >nul 2>nul
exit /b 0

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
