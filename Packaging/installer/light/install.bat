@echo off
chcp 65001 >nul
cd /d "%~dp0" || exit /b 1
REM Установка Open Voyah v@VERSION@-LIGHT. Запускать из папки релиза (бэкапы падают в .\backup).
REM Полный аналог install.sh для Windows.
REM LIGHT = только не-root функциональность: режимы вождения, автосвет, прогрев/статусы, дворники,
REM поездки, Power Hold, режим мойки, звук пешеходов, плавающая кнопка «Назад», установка приложений,
REM ярлыки приложений (обычный запуск). БЕЗ сплита/дока/VirtualDisplay, БЕЗ Frida и любой root-инъекции,
REM БЕЗ раздела «Кнопки на руле». init.logcat.sh системы НЕ трогаем.
set "YDNS_HELPER=%~dp0dns-overlay.bat"
if not exist "%YDNS_HELPER%" (
    echo !!! Не найден dns-overlay.bat - установка прервана до изменения устройства.
    exit /b 1
)
call "%YDNS_HELPER%" prepare-install
if errorlevel 1 (
    echo !!! Комплект DNS-overlay неполон - установка прервана до изменения устройства.
    exit /b 1
)

adb.exe root
adb.exe wait-for-device
adb.exe root

REM --- Гарантируем ЗАПИСЫВАЕМЫЙ /system ------------------------------------------------------
REM Light тоже пишет в /system (priv-app + whitelist привилегий), поэтому подготовка нужна ровно та
REM же, что и в full. Без этого на стоковой/после-OTA голове dm-verity держит /system read-only ->
REM push в /system даёт "I/O error" и установка падает. disable-verity применяется ТОЛЬКО после
REM РЕБУТА. Идемпотентно: если /system уже записываем (готовая голова) - ребута НЕ будет; иначе
REM снимаем verity, ОДИН раз перезагружаемся и продолжаем. adb remount = OverlayFS (устойчивее
REM сырого mount -o rw,remount).
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
call :backup_pull /system/priv-app/Native/Native.apk     Native.apk
call :backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml

REM NB: install — это ОБНОВЛЕНИЕ и СОХРАНЯЕТ настройки. RestoreMode ставится через -r (его data
REM остаётся); Native обновляется пушем APK в /system БЕЗ сноса data, поэтому локальные тумблеры Native
REM (autoLight / wiperCold / floatingBack) тоже сохраняются.
REM Полная чистка протухшего состояния Native (лечение краха zygote "data_de/null") вынесена в remove.bat.
echo === Native.apk в /system/priv-app (нужны привилегированные пермишены для CAN-функций) ===
REM /system уже сделан записываемым выше (verity, overlay) - отдельный remount не нужен.
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

adb.exe install -r -g restore_mode.apk
if errorlevel 1 (
    echo !!! RestoreMode не установлен - исправьте ошибку и повторите installer до перезагрузки.
    exit /b 1
)

echo === DNS для доступа через T-Box ===
set "YDNS_STATUS_FILE=%TEMP%\open_voyah_ydns_%RANDOM%_%RANDOM%.tmp"
call "%YDNS_HELPER%" status > "%YDNS_STATUS_FILE%"
if errorlevel 1 (
    del "%YDNS_STATUS_FILE%" >nul 2>nul
    echo !!! Не удалось определить текущее состояние DNS-overlay - финальная перезагрузка отменена.
    exit /b 1
)
set "YDNS_CURRENT="
set /p "YDNS_CURRENT=" < "%YDNS_STATUS_FILE%"
del "%YDNS_STATUS_FILE%" >nul 2>nul
if not defined YDNS_CURRENT (
    echo !!! DNS-overlay helper вернул пустое состояние - финальная перезагрузка отменена.
    exit /b 1
)
echo Текущее состояние DNS-overlay: %YDNS_CURRENT%
set "YDNS_REQUEST="
call "%YDNS_HELPER%" select "%YDNS_CURRENT%"
if errorlevel 1 (
    echo !!! Не удалось получить выбор DNS-overlay - финальная перезагрузка отменена.
    exit /b 1
)
if not defined YDNS_REQUEST set "YDNS_REQUEST=keep"
if /i "%YDNS_REQUEST%"=="on" goto :ovw_ydns_on
if /i "%YDNS_REQUEST%"=="off" goto :ovw_ydns_off
if /i "%YDNS_REQUEST%"=="keep" goto :ovw_ydns_keep
echo !!! Неизвестный выбор DNS-overlay: %YDNS_REQUEST%
exit /b 1

:ovw_ydns_on
call "%YDNS_HELPER%" install
if errorlevel 1 (
    echo !!! Установка DNS-overlay завершилась ошибкой - финальная перезагрузка отменена.
    exit /b 1
)
goto :ovw_ydns_done

:ovw_ydns_off
call "%YDNS_HELPER%" disable
if errorlevel 1 (
    echo !!! Отключение DNS-overlay завершилось ошибкой - финальная перезагрузка отменена.
    exit /b 1
)
goto :ovw_ydns_done

:ovw_ydns_keep
echo DNS-overlay: оставляем текущее состояние без изменений.

:ovw_ydns_done
REM Ребут нужен, чтобы менеджер пакетов перечитал privapp-whitelist для /system/priv-app.
adb.exe reboot
if errorlevel 1 (
    echo !!! Изменения подготовлены, но ADB не смог перезагрузить ГУ. Выполните reboot вручную.
    pause
    exit /b 1
)
echo "Press any key..."
pause
exit /b 0
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
