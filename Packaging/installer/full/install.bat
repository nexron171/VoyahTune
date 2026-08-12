@echo off
chcp 65001 >nul
cd /d "%~dp0" || exit /b 1
REM Установка Open Voyah v@VERSION@. Запускать из папки релиза (бэкапы падают в .\backup).
REM Ставит: Native (priv-app) + RestoreMode, whitelist привилегий, freeform, и Frida-обвязку —
REM   1) кнопки руля (steeringwheelkeys.js в keymanager: звёздочка 3090 и DVR 173, один onKeyEvent),
REM   2) VirtualDisplay-сплит (vd_bypass.js в system_server: обход ADD_TRUSTED_DISPLAY/INJECT_EVENTS),
REM   3) Apollo/ADAS master-gate (apollo_tech.js в com.qinggan.app.vehiclesetting).
REM Boot-хук = свои RC-сервисы /system/etc/init/voyahtune.*.rc (setenforce 0 + load.bin watchdog);
REM штатный /system/etc/init.logcat.sh больше не трогаем.
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

REM Update не наследует stale ON: до disable-verity и любых /system mutations принудительно ставим 0.
echo === Preflight безопасного состояния Apollo master ===
adb.exe shell settings put global open_voyah_apollo_master 0
if errorlevel 1 (
    echo !!! Не удалось выключить Apollo master - установка прервана до записи в /system.
    exit /b 1
)
set APOLLO_INSTALL_MASTER_STATE=
for /f "delims=" %%i in ('adb.exe shell settings get global open_voyah_apollo_master 2^>nul') do set APOLLO_INSTALL_MASTER_STATE=%%i
if not "%APOLLO_INSTALL_MASTER_STATE%"=="0" (
    echo !!! Apollo master не подтвердил состояние 0 - установка прервана до записи в /system.
    exit /b 1
)
echo   Apollo master=0 подтверждён.

REM Native full сам объявляет signature-permission для fail-closed CAN writer. Проверяем первого
REM владельца ДО disable-verity/remount: старый VoyahTweaks сделал бы новую установку несовместимой.
echo === Preflight владельца com.qinggan.permission.WRITE_CANBUS ===
adb.exe shell dumpsys package permissions >nul 2>nul
if errorlevel 1 (
    echo !!! PackageManager permissions недоступны - установка прервана до записи в /system.
    exit /b 1
)
set CANBUS_PERMISSION_PRESENT=0
adb.exe shell "dumpsys package permissions | grep -qF 'Permission [com.qinggan.permission.WRITE_CANBUS]'" >nul 2>nul
if not errorlevel 1 set CANBUS_PERMISSION_PRESENT=1
set CANBUS_PERMISSION_OWNER=
if "%CANBUS_PERMISSION_PRESENT%"=="1" for /f "tokens=2 delims==" %%i in ('adb.exe shell "dumpsys package permissions ^| grep -A 8 -F 'Permission [com.qinggan.permission.WRITE_CANBUS]' ^| grep -m 1 'sourcePackage='" 2^>nul') do set CANBUS_PERMISSION_OWNER=%%i
if "%CANBUS_PERMISSION_PRESENT%"=="0" goto :canbus_permission_ok
if "%CANBUS_PERMISSION_OWNER%"=="ru.big.town.anative" goto :canbus_permission_ok
if "%CANBUS_PERMISSION_OWNER%"=="" (
    echo !!! Владелец com.qinggan.permission.WRITE_CANBUS не определён однозначно.
) else (
    echo !!! com.qinggan.permission.WRITE_CANBUS уже принадлежит %CANBUS_PERMISSION_OWNER%.
)
echo     Удалите несовместимый пакет и повторите full install; /system ещё не изменялся.
exit /b 1
:canbus_permission_ok
if "%CANBUS_PERMISSION_PRESENT%"=="0" echo   Permission ещё не объявлен - его создаст full Native.
if "%CANBUS_PERMISSION_PRESENT%"=="1" echo   Permission уже принадлежит ru.big.town.anative - совместимое обновление.

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
call :backup_pull /data/local/bin/launcherdock.js        launcherdock.js
call :backup_pull /data/local/bin/multidisplay.js        multidisplay.js
call :backup_pull /data/local/bin/vd_bypass.js           vd_bypass.js
call :backup_pull_with_absent /data/local/bin/apollo_tech.js apollo_tech.js
if errorlevel 1 (
    echo !!! Apollo backup не создан - установка прервана до перезаписи файла.
    exit /b 1
)
call :backup_pull /data/local/bin/frida-inject           frida-inject
call :backup_pull /system/priv-app/Native/Native.apk     Native.apk
call :backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml

REM ВАЖНО: всё в /data/local/bin — доступно рано при загрузке (когда выполняется init.logcat.sh).
REM /sdcard монтируется позже, поэтому load.bin ТАМ держать нельзя (не запустится на буте).
echo === Frida-инфраструктура (руль + VirtualDisplay + Apollo/ADAS) ===
adb.exe shell "mkdir -p /data/local/bin"
adb.exe push load.bin              /data/local/bin/load.bin
adb.exe push steeringwheelkeys.js  /data/local/bin/steeringwheelkeys.js
adb.exe push launcherdock.js       /data/local/bin/launcherdock.js
adb.exe push multidisplay.js       /data/local/bin/multidisplay.js
adb.exe push vd_bypass.js          /data/local/bin/vd_bypass.js
REM Не даём живому watchdog увидеть частично переданный safety-sensitive hook.
adb.exe push apollo_tech.js /data/local/bin/apollo_tech.js.new
if errorlevel 1 (
    echo !!! Не удалось передать apollo_tech.js - установка прервана.
    exit /b 1
)
adb.exe shell "chmod 644 /data/local/bin/apollo_tech.js.new && mv -f /data/local/bin/apollo_tech.js.new /data/local/bin/apollo_tech.js"
if errorlevel 1 (
    adb.exe shell "rm -f /data/local/bin/apollo_tech.js.new"
    echo !!! Не удалось атомарно установить apollo_tech.js - установка прервана.
    exit /b 1
)
adb.exe push frida-inject-16.2.1-android-arm64 /data/local/bin/frida-inject
adb.exe shell "chmod 755 /data/local/bin/frida-inject /data/local/bin/load.bin"
adb.exe shell "chmod 644 /data/local/bin/steeringwheelkeys.js /data/local/bin/launcherdock.js /data/local/bin/vd_bypass.js /data/local/bin/multidisplay.js /data/local/bin/apollo_tech.js"

echo === Boot-хук: свои RC-сервисы (setenforce 0 + запуск load.bin), init.logcat.sh не трогаем ===
REM /system уже сделан записываемым выше (verity, overlay) - отдельный remount не нужен.
adb.exe shell "mkdir -p /system/etc/init"
adb.exe push voyahtune.setenforce.rc /system/etc/init/voyahtune.setenforce.rc
adb.exe push voyahtune.load.rc       /system/etc/init/voyahtune.load.rc
adb.exe push voyahtune.load.sh       /system/etc/init/voyahtune.load.sh
adb.exe shell "chmod 644 /system/etc/init/voyahtune.setenforce.rc /system/etc/init/voyahtune.load.rc"
adb.exe shell "chmod 755 /system/etc/init/voyahtune.load.sh"

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

REM Одноразовый симметричный backup для нового файла: .absent фиксирует исходное отсутствие.
:backup_pull_with_absent
if exist "%BACKUP_DIR%\%~2" (
    echo Backup: исходное состояние %~2 уже сохранено - пропуск
    exit /b 0
)
if exist "%BACKUP_DIR%\%~2.absent" (
    echo Backup: исходное состояние %~2 уже сохранено - пропуск
    exit /b 0
)
set APOLLO_REMOTE_STATE=
for /f "delims=" %%i in ('adb.exe shell "if [ -e %1 ]; then echo PRESENT; else echo ABSENT; fi" 2^>nul') do set APOLLO_REMOTE_STATE=%%i
if "%APOLLO_REMOTE_STATE%"=="ABSENT" (
    type nul > "%BACKUP_DIR%\%~2.absent"
    if errorlevel 1 (
        echo !!! Не удалось создать %BACKUP_DIR%\%~2.absent
        exit /b 1
    )
    echo Backup: %1 изначально отсутствует -^> %BACKUP_DIR%\%~2.absent
    exit /b 0
)
if not "%APOLLO_REMOTE_STATE%"=="PRESENT" (
    echo !!! Не удалось определить исходное состояние %1
    exit /b 1
)
del "%BACKUP_DIR%\%~2.new" 1>nul 2>nul
adb.exe pull %1 "%BACKUP_DIR%\%~2.new" 1>nul 2>nul
if errorlevel 1 (
    del "%BACKUP_DIR%\%~2.new" 1>nul 2>nul
    echo !!! Не удалось сохранить существующий %1
    exit /b 1
)
move /y "%BACKUP_DIR%\%~2.new" "%BACKUP_DIR%\%~2" 1>nul 2>nul
if errorlevel 1 (
    del "%BACKUP_DIR%\%~2.new" 1>nul 2>nul
    echo !!! Не удалось атомарно зафиксировать backup существующего %1
    exit /b 1
)
echo Backup: %1 -^> %BACKUP_DIR%\%~2
exit /b 0
