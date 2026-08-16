@echo off
chcp 65001 >nul
cd /d "%~dp0" || exit /b 1
REM Установка Open Voyah v@VERSION@. Запускать из папки релиза (бэкапы падают в .\backup).
REM Ставит: Native (priv-app) + RestoreMode, whitelist привилегий, freeform, и Frida-обвязку —
REM   1) кнопки руля (steeringwheelkeys.js в keymanager: звёздочка 3090 и DVR 173, один onKeyEvent),
REM   2) VirtualDisplay-сплит (vd_bypass.js в system_server: обход ADD_TRUSTED_DISPLAY/INJECT_EVENTS),
REM   3) dormant legacy Apollo diagnostic (direct-only не инжектит VehicleSetting).
REM Boot-хук = свои RC-сервисы /system/etc/init/voyahtune.*.rc (setenforce 0 + load.bin watchdog).
REM Штатный /system/etc/init.logcat.sh не меняем, кроме узкой миграции нашего legacy-файла.
for %%F in (load.bin steeringwheelkeys.js launcherdock.js multidisplay.js vd_bypass.js apollo_tech.js frida-inject-16.2.1-android-arm64 voyahtune.load.rc voyahtune.load.sh init.logcat.original.sh native.apk restore_mode.apk privapp-permissions-ru.big.town.anative.xml) do if not exist "%%F" (
    echo !!! Отсутствует обязательный файл %%F - устройство не изменялось.
    exit /b 1
)

adb.exe root
adb.exe wait-for-device
adb.exe root

REM Direct-only update не наследует legacy opt-in/stale gate.
echo === Preflight direct-only Apollo ^(VehicleSetting hook OFF^) ===
call :put_apollo_safe_key open_voyah_apollo_legacy_hook_enabled
if errorlevel 1 exit /b 1
call :put_apollo_safe_key open_voyah_apollo_master
if errorlevel 1 exit /b 1
call :put_apollo_safe_key open_voyah_apollo_profile_supported
if errorlevel 1 exit /b 1
call :put_apollo_safe_key open_voyah_apollo_profile_heartbeat
if errorlevel 1 exit /b 1
echo   Legacy opt-in, master, profile и heartbeat закрыты.

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
timeout /t 5 /nobreak >nul
goto :wait_boot_ovw
:booted_ovw
timeout /t 3 /nobreak >nul
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
if not exist "%BACKUP_DIR%" (
    mkdir "%BACKUP_DIR%"
    if errorlevel 1 (
        echo !!! Не удалось подготовить %BACKUP_DIR% - установка прервана до перезаписи файлов.
        exit /b 1
    )
)

echo === Бэкап перезаписываемых файлов в %BACKUP_DIR%\ ===
call :backup_pull /data/local/bin/load.bin               load.bin
if errorlevel 1 exit /b 1
call :backup_pull /data/local/bin/steeringwheelkeys.js   steeringwheelkeys.js
if errorlevel 1 exit /b 1
call :backup_pull /data/local/bin/launcherdock.js        launcherdock.js
if errorlevel 1 exit /b 1
call :backup_pull /data/local/bin/multidisplay.js        multidisplay.js
if errorlevel 1 exit /b 1
call :backup_pull /data/local/bin/vd_bypass.js           vd_bypass.js
if errorlevel 1 exit /b 1
call :backup_pull_with_absent /data/local/bin/apollo_tech.js apollo_tech.js
if errorlevel 1 (
    echo !!! Apollo backup не создан - установка прервана до перезаписи файла.
    exit /b 1
)
call :backup_pull /data/local/bin/frida-inject           frida-inject
if errorlevel 1 exit /b 1
call :backup_pull /system/priv-app/Native/Native.apk     Native.apk
if errorlevel 1 exit /b 1
call :backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml
if errorlevel 1 exit /b 1

REM ВАЖНО: всё в /data/local/bin доступно загрузочному RC-сервису.
REM /sdcard монтируется позже, поэтому load.bin ТАМ держать нельзя (не запустится на буте).
echo === Frida-инфраструктура (руль + VirtualDisplay + dormant Apollo diagnostic) ===
adb.exe shell "mkdir -p /data/local/bin"
if errorlevel 1 exit /b 1
call :install_required_data_file load.bin /data/local/bin/load.bin 755
if errorlevel 1 exit /b 1
call :install_required_data_file steeringwheelkeys.js /data/local/bin/steeringwheelkeys.js 644
if errorlevel 1 exit /b 1
call :install_required_data_file launcherdock.js /data/local/bin/launcherdock.js 644
if errorlevel 1 exit /b 1
call :install_required_data_file multidisplay.js /data/local/bin/multidisplay.js 644
if errorlevel 1 exit /b 1
call :install_required_data_file vd_bypass.js /data/local/bin/vd_bypass.js 644
if errorlevel 1 exit /b 1
call :install_required_data_file apollo_tech.js /data/local/bin/apollo_tech.js 644
if errorlevel 1 exit /b 1
call :install_required_data_file frida-inject-16.2.1-android-arm64 /data/local/bin/frida-inject 755
if errorlevel 1 exit /b 1

echo === Миграция boot-hook предыдущего full-релиза ===
call :migrate_legacy_init_logcat
if errorlevel 1 exit /b 1

echo === Boot-хук: свои RC-сервисы (setenforce 0 + запуск load.bin) ===
REM /system уже сделан записываемым выше (verity, overlay) - отдельный remount не нужен.
call :install_boot_hooks
set "BOOT_HOOK_INSTALL_STATUS=%errorlevel%"
if "%BOOT_HOOK_INSTALL_STATUS%"=="0" goto :boot_hooks_installed
if "%BOOT_HOOK_INSTALL_STATUS%"=="1" call :handle_safe_boot_hook_failure
if "%BOOT_HOOK_INSTALL_STATUS%"=="2" echo !!! RC rollback не подтверждён; чистый init.logcat.sh оставлен, чтобы не создать второй boot-hook.
if "%BOOT_HOOK_INSTALL_STATUS%"=="2" echo     Не перезагружайте ГУ; восстановите ADB и повторите installer.
exit /b 1
:boot_hooks_installed
set "LEGACY_INIT_MIGRATED=0"

REM NB: install — это ОБНОВЛЕНИЕ и СОХРАНЯЕТ настройки (RestoreMode через -r, Native пушем APK
REM в /system без сноса data). Полная чистка протухшего состояния Native вынесена в remove.bat.
REM Порядок для чистого baseline (после порчи от старого remove): remove.bat -> install.bat.
echo === Native.apk в /system/priv-app ===
adb.exe shell "mkdir -p /system/priv-app/Native && chmod 755 /system/priv-app/Native"
if errorlevel 1 exit /b 1
call :install_required_system_file native.apk /system/priv-app/.Native.apk.voyahtune.new /system/priv-app/Native/Native.apk 644
if errorlevel 1 exit /b 1
adb.exe shell "ls -all /system/priv-app/Native"

REM Whitelist привилегированных пермишенов (нужен на enforce-ROM: FORCE_STOP/WRITE_SECURE_SETTINGS/…)
adb.exe shell "mkdir -p /system/etc/permissions"
if errorlevel 1 exit /b 1
call :install_required_system_file privapp-permissions-ru.big.town.anative.xml /system/etc/.privapp-permissions-ru.big.town.anative.xml.voyahtune.new /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml 644
if errorlevel 1 exit /b 1

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

echo === Опциональный DNS для доступа через T-Box ===
set "YDNS_ANSWER=n"
set /p "YDNS_ANSWER=Install Yandex DNS? y/n "
REM Не подставляем ввод пользователя обратно в cmd-синтаксис.
set YDNS_ANSWER 2>nul | findstr.exe /i /x /c:"YDNS_ANSWER=y" >nul
if errorlevel 1 goto :ovw_ydns_skip

set "YDNS_HELPER=%~dp0dns-overlay.bat"
if not exist "%YDNS_HELPER%" (
    echo !!! Не найден dns-overlay.bat - финальная перезагрузка отменена.
    exit /b 1
)
call "%YDNS_HELPER%" prepare
if errorlevel 1 (
    echo !!! Комплект DNS-overlay неполон - финальная перезагрузка отменена.
    exit /b 1
)
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
REM Проверяем device output как данные до любой подстановки в cmd-строку.
set YDNS_CURRENT 2>nul | findstr.exe /i /x /c:"YDNS_CURRENT=on" /c:"YDNS_CURRENT=off" /c:"YDNS_CURRENT=external" /c:"YDNS_CURRENT=broken" >nul
if errorlevel 1 (
    echo !!! DNS-overlay helper вернул неизвестное состояние - финальная перезагрузка отменена.
    exit /b 1
)
echo Текущее состояние DNS-overlay: %YDNS_CURRENT%
if /i "%YDNS_CURRENT%"=="external" goto :ovw_ydns_keep
if /i "%YDNS_CURRENT%"=="broken" goto :ovw_ydns_keep
if /i "%YDNS_CURRENT%"=="on" goto :ovw_ydns_on
if /i "%YDNS_CURRENT%"=="off" goto :ovw_ydns_on
echo !!! Не удалось классифицировать состояние DNS-overlay - финальная перезагрузка отменена.
exit /b 1

:ovw_ydns_on
call "%YDNS_HELPER%" prepare-install
if errorlevel 1 (
    echo !!! Комплект DNS-overlay не прошёл проверку - финальная перезагрузка отменена.
    exit /b 1
)
call "%YDNS_HELPER%" install
if errorlevel 1 (
    echo !!! Установка DNS-overlay завершилась ошибкой - финальная перезагрузка отменена.
    exit /b 1
)
goto :ovw_ydns_done

:ovw_ydns_keep
echo DNS-overlay: состояние %YDNS_CURRENT% нельзя безопасно заменить; оставляем его без изменений.
goto :ovw_ydns_done

:ovw_ydns_skip
echo Yandex DNS installation skipped; current DNS state is unchanged.

:ovw_ydns_done
adb.exe reboot
if errorlevel 1 (
    echo !!! Изменения подготовлены, но ADB не смог перезагрузить ГУ. Выполните reboot вручную.
    pause
    exit /b 1
)
echo Installation complete.
exit /b 0
goto :eof

:put_apollo_safe_key
adb.exe shell settings put global %~1 0
if errorlevel 1 (
    echo !!! Не удалось записать %~1=0 - установка прервана до записи в /system.
    exit /b 1
)
set "APOLLO_SAFE_STATE="
for /f "delims=" %%i in ('adb.exe shell settings get global %~1 2^>nul') do set "APOLLO_SAFE_STATE=%%i"
if not "%APOLLO_SAFE_STATE%"=="0" (
    echo !!! %~1 не подтвердил 0 - установка прервана до записи в /system.
    exit /b 1
)
exit /b 0

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
    for %%A in ("%BACKUP_DIR%\%~2") do if %%~zA LEQ 0 (
        echo !!! Существующий backup %BACKUP_DIR%\%~2 пуст или не является файлом.
        exit /b 1
    )
    echo Backup: %BACKUP_DIR%\%~2 уже есть - пропуск ^(сохраняем оригинал^)
    exit /b 0
)
set "BACKUP_REMOTE_STATE="
for /f "delims=" %%i in ('adb.exe shell "if [ -f %1 ]; then echo PRESENT; elif [ -e %1 ]; then echo ERROR; else echo ABSENT; fi" 2^>nul') do set "BACKUP_REMOTE_STATE=%%i"
if "%BACKUP_REMOTE_STATE%"=="ABSENT" (
    echo Backup: %1 отсутствует, пропуск
    exit /b 0
)
if not "%BACKUP_REMOTE_STATE%"=="PRESENT" (
    echo !!! Не удалось безопасно прочитать %1 перед backup.
    exit /b 1
)
del "%BACKUP_DIR%\%~2.new" 1>nul 2>nul
adb.exe pull %1 "%BACKUP_DIR%\%~2.new" 1>nul 2>nul
if errorlevel 1 goto :backup_pull_failed
for %%A in ("%BACKUP_DIR%\%~2.new") do if %%~zA LEQ 0 goto :backup_pull_failed
move /y "%BACKUP_DIR%\%~2.new" "%BACKUP_DIR%\%~2" 1>nul 2>nul
if errorlevel 1 goto :backup_pull_failed
echo Backup: %1 -^> %BACKUP_DIR%\%~2
exit /b 0

:backup_pull_failed
del "%BACKUP_DIR%\%~2.new" 1>nul 2>nul
echo !!! Не удалось сохранить существующий %1 - установка прервана.
exit /b 1

REM Одноразовый симметричный backup для нового файла: .absent фиксирует исходное отсутствие.
:backup_pull_with_absent
if exist "%BACKUP_DIR%\%~2" (
    for %%A in ("%BACKUP_DIR%\%~2") do if %%~zA LEQ 0 (
        echo !!! Существующий backup %BACKUP_DIR%\%~2 пуст или не является файлом.
        exit /b 1
    )
    echo Backup: исходное состояние %~2 уже сохранено - пропуск
    exit /b 0
)
if exist "%BACKUP_DIR%\%~2.absent" (
    if exist "%BACKUP_DIR%\%~2.absent\NUL" (
        echo !!! Marker %BACKUP_DIR%\%~2.absent не является файлом.
        exit /b 1
    )
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

REM Мигрирует только предыдущий VoyahTune init.logcat.sh с явным ownership-marker.
:migrate_legacy_init_logcat
set "LEGACY_INIT_STATE_FILE=%TEMP%\open_voyah_legacy_init_%RANDOM%_%RANDOM%.tmp"
set "LEGACY_INIT_ROLLBACK_SOURCE=backup\init.logcat.voyahtune-legacy.sh"
set "LEGACY_INIT_MIGRATED=0"
set "LEGACY_INIT_STATE="
adb.exe shell "if [ ! -e /system/etc/init.logcat.sh ]; then echo MISSING; elif [ ! -f /system/etc/init.logcat.sh ]; then echo ERROR; else grep -qF '# init.logcat.sh Open Voyah:' /system/etc/init.logcat.sh 2>/dev/null; legacy_grep_status=$?; if [ $legacy_grep_status -eq 0 ]; then echo LEGACY; elif [ $legacy_grep_status -eq 1 ]; then echo CLEAN; else echo ERROR; fi; fi" > "%LEGACY_INIT_STATE_FILE%" 2>nul
if errorlevel 1 goto :legacy_init_check_failed
set /p "LEGACY_INIT_STATE=" < "%LEGACY_INIT_STATE_FILE%"
del "%LEGACY_INIT_STATE_FILE%" >nul 2>nul
if "%LEGACY_INIT_STATE%"=="CLEAN" (
    echo   Штатный init.logcat.sh не содержит marker VoyahTune - оставляем без изменений.
    exit /b 0
)
if "%LEGACY_INIT_STATE%"=="MISSING" (
    echo   /system/etc/init.logcat.sh отсутствует - миграция legacy-hook не требуется.
    exit /b 0
)
if not "%LEGACY_INIT_STATE%"=="LEGACY" (
    echo !!! Неизвестный результат проверки init.logcat.sh: %LEGACY_INIT_STATE%
    exit /b 1
)

if not exist "backup" mkdir "backup"
del "%LEGACY_INIT_ROLLBACK_SOURCE%.new" >nul 2>nul
adb.exe pull /system/etc/init.logcat.sh "%LEGACY_INIT_ROLLBACK_SOURCE%.new" >nul 2>nul
if errorlevel 1 (
    echo !!! Не удалось сохранить legacy init.logcat.sh для rollback.
    exit /b 1
)
for %%A in ("%LEGACY_INIT_ROLLBACK_SOURCE%.new") do if %%~zA LEQ 0 (
    del "%LEGACY_INIT_ROLLBACK_SOURCE%.new" >nul 2>nul
    echo !!! Rollback-копия legacy init.logcat.sh пуста.
    exit /b 1
)
findstr /L /C:"# init.logcat.sh Open Voyah:" "%LEGACY_INIT_ROLLBACK_SOURCE%.new" >nul 2>nul
if errorlevel 1 (
    del "%LEGACY_INIT_ROLLBACK_SOURCE%.new" >nul 2>nul
    echo !!! Rollback-копия legacy init.logcat.sh не прошла проверку.
    exit /b 1
)
move /y "%LEGACY_INIT_ROLLBACK_SOURCE%.new" "%LEGACY_INIT_ROLLBACK_SOURCE%" >nul 2>nul
if errorlevel 1 (
    del "%LEGACY_INIT_ROLLBACK_SOURCE%.new" >nul 2>nul
    echo !!! Не удалось зафиксировать rollback-копию legacy init.logcat.sh.
    exit /b 1
)

set "LEGACY_INIT_SOURCE=init.logcat.original.sh"
if not exist "backup\init.logcat.sh" goto :legacy_init_source_selected
call :validate_legacy_init_source "backup\init.logcat.sh"
if not "%LEGACY_INIT_VALID%"=="1" goto :legacy_init_source_selected
set "LEGACY_INIT_SOURCE=backup\init.logcat.sh"
:legacy_init_source_selected
call :validate_legacy_init_source "%LEGACY_INIT_SOURCE%"
if not "%LEGACY_INIT_VALID%"=="1" (
    echo !!! Найден legacy init.logcat.sh, но %LEGACY_INIT_SOURCE% не прошёл проверку.
    exit /b 1
)
if "%LEGACY_INIT_SOURCE%"=="backup\init.logcat.sh" (
    echo   Найден OEM-backup предыдущего установщика: %LEGACY_INIT_SOURCE%
) else (
    echo   Используем проверенный чистый init.logcat.original.sh.
)

adb.exe push "%LEGACY_INIT_SOURCE%" /system/etc/init.logcat.sh.voyahtune.new
if errorlevel 1 goto :legacy_init_publish_failed
set "LEGACY_INIT_MIGRATED=1"
adb.exe shell "chown 0:0 /system/etc/init.logcat.sh.voyahtune.new && chmod 644 /system/etc/init.logcat.sh.voyahtune.new && /system/bin/sh -n /system/etc/init.logcat.sh.voyahtune.new && restorecon /system/etc/init.logcat.sh.voyahtune.new && mv -f /system/etc/init.logcat.sh.voyahtune.new /system/etc/init.logcat.sh && restorecon /system/etc/init.logcat.sh && sync"
if errorlevel 1 (
    call :rollback_legacy_init_logcat
    if errorlevel 1 echo !!! Rollback init.logcat.sh не подтверждён. Не перезагружайте ГУ; повторите installer.
    goto :legacy_init_publish_failed
)

set "LEGACY_INIT_STATE="
adb.exe shell "if [ ! -e /system/etc/init.logcat.sh ]; then echo MISSING; elif [ ! -f /system/etc/init.logcat.sh ]; then echo ERROR; else grep -qF '# init.logcat.sh Open Voyah:' /system/etc/init.logcat.sh 2>/dev/null; legacy_grep_status=$?; if [ $legacy_grep_status -eq 0 ]; then echo LEGACY; elif [ $legacy_grep_status -eq 1 ]; then echo CLEAN; else echo ERROR; fi; fi" > "%LEGACY_INIT_STATE_FILE%" 2>nul
if errorlevel 1 goto :legacy_init_check_failed
set /p "LEGACY_INIT_STATE=" < "%LEGACY_INIT_STATE_FILE%"
del "%LEGACY_INIT_STATE_FILE%" >nul 2>nul
if not "%LEGACY_INIT_STATE%"=="CLEAN" (
    call :rollback_legacy_init_logcat
    if errorlevel 1 echo !!! Rollback init.logcat.sh не подтверждён. Не перезагружайте ГУ; повторите installer.
    echo !!! Legacy-marker остался после восстановления init.logcat.sh.
    exit /b 1
)
echo   Legacy init.logcat.sh успешно удалён из boot path.
exit /b 0

:legacy_init_check_failed
del "%LEGACY_INIT_STATE_FILE%" >nul 2>nul
call :rollback_legacy_init_logcat
if errorlevel 1 echo !!! Rollback init.logcat.sh не подтверждён. Не перезагружайте ГУ; повторите installer.
echo !!! Не удалось проверить /system/etc/init.logcat.sh - установка прервана.
exit /b 1

:legacy_init_publish_failed
adb.exe shell "rm -f /system/etc/init.logcat.sh.voyahtune.new" >nul 2>nul
echo !!! Не удалось атомарно восстановить init.logcat.sh - установка прервана.
exit /b 1

:validate_legacy_init_source
set "LEGACY_INIT_VALID=0"
if not exist "%~1" exit /b 0
for %%A in ("%~1") do if %%~zA LEQ 0 exit /b 0
REM Device-derived content must stay pipeline data: never expand its first line into cmd syntax.
findstr /N /R "^" "%~1" 2>nul | findstr /L /X /C:"1:#!/system/bin/sh" >nul 2>nul
if errorlevel 1 exit /b 0
findstr /L /C:"/system/bin/logcat" "%~1" >nul 2>nul
if errorlevel 1 exit /b 0
findstr /L /C:"# init.logcat.sh Open Voyah:" "%~1" >nul 2>nul
if not errorlevel 1 exit /b 0
set "LEGACY_INIT_VALID=1"
exit /b 0

:rollback_legacy_init_logcat
if not "%LEGACY_INIT_MIGRATED%"=="1" exit /b 0
if not exist "%LEGACY_INIT_ROLLBACK_SOURCE%" exit /b 1
for %%A in ("%LEGACY_INIT_ROLLBACK_SOURCE%") do if %%~zA LEQ 0 exit /b 1
findstr /L /C:"# init.logcat.sh Open Voyah:" "%LEGACY_INIT_ROLLBACK_SOURCE%" >nul 2>nul
if errorlevel 1 exit /b 1
adb.exe push "%LEGACY_INIT_ROLLBACK_SOURCE%" /system/etc/init.logcat.sh.voyahtune.rollback
if errorlevel 1 exit /b 1
adb.exe shell "chown 0:0 /system/etc/init.logcat.sh.voyahtune.rollback && chmod 644 /system/etc/init.logcat.sh.voyahtune.rollback && /system/bin/sh -n /system/etc/init.logcat.sh.voyahtune.rollback && restorecon /system/etc/init.logcat.sh.voyahtune.rollback && mv -f /system/etc/init.logcat.sh.voyahtune.rollback /system/etc/init.logcat.sh && restorecon /system/etc/init.logcat.sh && sync"
if errorlevel 1 exit /b 1
set "LEGACY_INIT_STATE="
adb.exe shell "if [ ! -e /system/etc/init.logcat.sh ]; then echo MISSING; elif [ ! -f /system/etc/init.logcat.sh ]; then echo ERROR; else grep -qF '# init.logcat.sh Open Voyah:' /system/etc/init.logcat.sh 2>/dev/null; legacy_grep_status=$?; if [ $legacy_grep_status -eq 0 ]; then echo LEGACY; elif [ $legacy_grep_status -eq 1 ]; then echo CLEAN; else echo ERROR; fi; fi" > "%LEGACY_INIT_STATE_FILE%" 2>nul
if errorlevel 1 exit /b 1
set /p "LEGACY_INIT_STATE=" < "%LEGACY_INIT_STATE_FILE%"
del "%LEGACY_INIT_STATE_FILE%" >nul 2>nul
if not "%LEGACY_INIT_STATE%"=="LEGACY" exit /b 1
set "LEGACY_INIT_MIGRATED=0"
echo   Legacy init.logcat.sh восстановлен после неудачной установки RC.
exit /b 0

:install_required_data_file
adb.exe push "%~1" "%~2.voyahtune.new"
if errorlevel 1 goto :install_required_data_file_failed
adb.exe shell "chown 0:0 '%~2.voyahtune.new' && chmod '%~3' '%~2.voyahtune.new' && mv -f '%~2.voyahtune.new' '%~2' && test -f '%~2'"
if errorlevel 1 goto :install_required_data_file_failed
exit /b 0

:install_required_data_file_failed
adb.exe shell "rm -f '%~2.voyahtune.new'" >nul 2>nul
echo !!! Не удалось атомарно установить %~2 - установка прервана.
exit /b 1

:install_required_system_file
adb.exe push "%~1" "%~2"
if errorlevel 1 goto :install_required_system_file_failed
adb.exe shell "chown 0:0 '%~2' && chmod '%~4' '%~2' && restorecon '%~2' && mv -f '%~2' '%~3' && restorecon '%~3' && sync && test -f '%~3'"
if errorlevel 1 goto :install_required_system_file_failed
exit /b 0

:install_required_system_file_failed
adb.exe shell "rm -f '%~2'" >nul 2>nul
echo !!! Не удалось атомарно установить %~3 - установка прервана.
exit /b 1

REM RC-stage держим вне /system/etc/init: init парсит там все regular files, независимо от suffix.
:boot_hook_cleanup_stage
adb.exe shell "rm -f /system/etc/.voyahtune.setenforce.rc.new /system/etc/.voyahtune.load.rc.new /system/etc/.voyahtune.load.sh.new /system/etc/.voyahtune.setenforce.rc.rollback /system/etc/.voyahtune.load.rc.rollback /system/etc/.voyahtune.load.sh.rollback" >nul 2>nul
exit /b %errorlevel%

:boot_hook_cleanup_snapshot
adb.exe shell "rm -f /system/etc/.voyahtune.setenforce.rc.previous /system/etc/.voyahtune.setenforce.rc.absent /system/etc/.voyahtune.load.rc.previous /system/etc/.voyahtune.load.rc.absent /system/etc/.voyahtune.load.sh.previous /system/etc/.voyahtune.load.sh.absent" >nul 2>nul
exit /b %errorlevel%

:read_boot_hook_final_state
set "BOOT_HOOK_FINAL_STATE_FILE=%TEMP%\open_voyah_boot_final_%RANDOM%_%RANDOM%.tmp"
set "BOOT_HOOK_FINAL_STATE="
adb.exe shell "if [ -x /system/etc/init.voyahtune.load.sh ] && grep -qF '/data/local/bin/load.bin' /system/etc/init.voyahtune.load.sh && [ -r /system/etc/init/voyahtune.load.rc ] && grep -qF 'on post-fs-data' /system/etc/init/voyahtune.load.rc && grep -qF '/system/bin/setenforce 0' /system/etc/init/voyahtune.load.rc && grep -qF 'service voyahtune_load' /system/etc/init/voyahtune.load.rc && grep -qF 'on property:sys.boot_completed=1' /system/etc/init/voyahtune.load.rc && grep -qF 'enable voyahtune_load' /system/etc/init/voyahtune.load.rc; then echo READY; elif [ ! -e /system/etc/init.voyahtune.load.sh ] && [ ! -e /system/etc/init/voyahtune.setenforce.rc ] && [ ! -e /system/etc/init/voyahtune.load.rc ]; then echo ABSENT; else echo PARTIAL; fi" > "%BOOT_HOOK_FINAL_STATE_FILE%" 2>nul
if errorlevel 1 (
    del "%BOOT_HOOK_FINAL_STATE_FILE%" >nul 2>nul
    exit /b 1
)
set /p "BOOT_HOOK_FINAL_STATE=" < "%BOOT_HOOK_FINAL_STATE_FILE%"
del "%BOOT_HOOK_FINAL_STATE_FILE%" >nul 2>nul
if "%BOOT_HOOK_FINAL_STATE%"=="READY" exit /b 0
if "%BOOT_HOOK_FINAL_STATE%"=="ABSENT" exit /b 0
if "%BOOT_HOOK_FINAL_STATE%"=="PARTIAL" exit /b 0
exit /b 1

:handle_safe_boot_hook_failure
call :read_boot_hook_final_state
if errorlevel 1 (
    echo !!! Не удалось проверить RC после rollback; legacy hook не возвращаем во избежание двух boot-path.
    echo     Не перезагружайте ГУ; восстановите ADB и повторите installer.
    exit /b 0
)
if "%BOOT_HOOK_FINAL_STATE%"=="READY" (
    echo   Предыдущий полный RC-комплект сохранён; legacy init.logcat.sh не возвращаем.
    exit /b 0
)
if "%BOOT_HOOK_FINAL_STATE%"=="PARTIAL" (
    echo !!! После rollback остался неполный RC-комплект; legacy hook не возвращаем во избежание двух boot-path.
    echo     Не перезагружайте ГУ; повторите installer.
    exit /b 0
)
call :rollback_legacy_init_logcat
if errorlevel 1 echo !!! Новый RC и legacy rollback не установлены. Не перезагружайте ГУ; повторите installer.
exit /b 0

:boot_hook_snapshot
call :boot_hook_cleanup_snapshot
if errorlevel 1 exit /b 1
adb.exe shell "if [ -f /system/etc/init/voyahtune.setenforce.rc ]; then cp -p /system/etc/init/voyahtune.setenforce.rc /system/etc/.voyahtune.setenforce.rc.previous; else touch /system/etc/.voyahtune.setenforce.rc.absent; fi && if [ -f /system/etc/init/voyahtune.load.rc ]; then cp -p /system/etc/init/voyahtune.load.rc /system/etc/.voyahtune.load.rc.previous; else touch /system/etc/.voyahtune.load.rc.absent; fi && if [ -f /system/etc/init.voyahtune.load.sh ]; then cp -p /system/etc/init.voyahtune.load.sh /system/etc/.voyahtune.load.sh.previous; else touch /system/etc/.voyahtune.load.sh.absent; fi && sync"
exit /b %errorlevel%

:boot_hook_rollback
set "BOOT_HOOK_ROLLBACK_FAILED=0"
adb.exe shell "restore_one() { if [ -f $1.previous ]; then cp -p $1.previous $1.rollback && mv -f $1.rollback $2; elif [ -f $1.absent ]; then rm -f $2; else return 1; fi; }; if [ -f /system/etc/.voyahtune.load.rc.absent ]; then rm -f /system/etc/init/voyahtune.load.rc && restore_one /system/etc/.voyahtune.load.sh /system/etc/init.voyahtune.load.sh && restore_one /system/etc/.voyahtune.setenforce.rc /system/etc/init/voyahtune.setenforce.rc; elif [ -f /system/etc/.voyahtune.load.rc.previous ]; then restore_one /system/etc/.voyahtune.load.sh /system/etc/init.voyahtune.load.sh && restore_one /system/etc/.voyahtune.setenforce.rc /system/etc/init/voyahtune.setenforce.rc && restore_one /system/etc/.voyahtune.load.rc /system/etc/init/voyahtune.load.rc; else exit 1; fi && for f in /system/etc/init/voyahtune.setenforce.rc /system/etc/init/voyahtune.load.rc /system/etc/init.voyahtune.load.sh; do [ ! -e $f ] || restorecon $f || exit 1; done && sync" >nul 2>nul
if errorlevel 1 set "BOOT_HOOK_ROLLBACK_FAILED=1"
call :boot_hook_cleanup_stage
if "%BOOT_HOOK_ROLLBACK_FAILED%"=="1" exit /b 1
call :boot_hook_cleanup_snapshot
exit /b 0

:install_boot_hooks
if not exist "voyahtune.load.rc" goto :boot_hook_local_missing
if not exist "voyahtune.load.sh" goto :boot_hook_local_missing
adb.exe shell "mkdir -p /system/etc/init"
if errorlevel 1 (
    echo !!! Не удалось подготовить /system/etc/init - boot-hook не установлен.
    exit /b 1
)
call :boot_hook_cleanup_stage
adb.exe push voyahtune.load.sh /system/etc/.voyahtune.load.sh.new
if errorlevel 1 goto :boot_hook_stage_failed
adb.exe push voyahtune.load.rc /system/etc/.voyahtune.load.rc.new
if errorlevel 1 goto :boot_hook_stage_failed
adb.exe shell "chown 0:0 /system/etc/.voyahtune.load.sh.new /system/etc/.voyahtune.load.rc.new && chmod 755 /system/etc/.voyahtune.load.sh.new && chmod 644 /system/etc/.voyahtune.load.rc.new && grep -qF '/data/local/bin/load.bin' /system/etc/.voyahtune.load.sh.new && grep -qF 'on post-fs-data' /system/etc/.voyahtune.load.rc.new && grep -qF '/system/bin/setenforce 0' /system/etc/.voyahtune.load.rc.new && grep -qF 'service voyahtune_load' /system/etc/.voyahtune.load.rc.new && grep -qF 'on property:sys.boot_completed=1' /system/etc/.voyahtune.load.rc.new && grep -qF 'enable voyahtune_load' /system/etc/.voyahtune.load.rc.new && restorecon /system/etc/.voyahtune.load.sh.new /system/etc/.voyahtune.load.rc.new && sync"
if errorlevel 1 goto :boot_hook_stage_failed
call :boot_hook_snapshot
if errorlevel 1 goto :boot_hook_snapshot_failed

REM Composite load.rc содержит setenforce и service: один rename активирует полный комплект.
adb.exe shell "mv -f /system/etc/.voyahtune.load.sh.new /system/etc/init.voyahtune.load.sh && mv -f /system/etc/.voyahtune.load.rc.new /system/etc/init/voyahtune.load.rc && restorecon /system/etc/init.voyahtune.load.sh /system/etc/init/voyahtune.load.rc && sync"
if errorlevel 1 goto :boot_hook_publish_failed

set "BOOT_HOOK_STATE_FILE=%TEMP%\open_voyah_boot_hook_%RANDOM%_%RANDOM%.tmp"
set "BOOT_HOOK_STATE="
adb.exe shell "if [ -x /system/etc/init.voyahtune.load.sh ] && grep -qF '/data/local/bin/load.bin' /system/etc/init.voyahtune.load.sh && [ -r /system/etc/init/voyahtune.load.rc ] && grep -qF 'on post-fs-data' /system/etc/init/voyahtune.load.rc && grep -qF '/system/bin/setenforce 0' /system/etc/init/voyahtune.load.rc && grep -qF 'service voyahtune_load' /system/etc/init/voyahtune.load.rc && grep -qF 'on property:sys.boot_completed=1' /system/etc/init/voyahtune.load.rc && grep -qF 'enable voyahtune_load' /system/etc/init/voyahtune.load.rc; then echo READY; else echo BROKEN; fi" > "%BOOT_HOOK_STATE_FILE%" 2>nul
if errorlevel 1 (
    del "%BOOT_HOOK_STATE_FILE%" >nul 2>nul
    goto :boot_hook_verify_failed
)
set /p "BOOT_HOOK_STATE=" < "%BOOT_HOOK_STATE_FILE%"
del "%BOOT_HOOK_STATE_FILE%" >nul 2>nul
if not "%BOOT_HOOK_STATE%"=="READY" goto :boot_hook_verify_failed
adb.exe shell "rm -f /system/etc/init/voyahtune.setenforce.rc && test ! -e /system/etc/init/voyahtune.setenforce.rc && sync"
if errorlevel 1 echo   ПРЕДУПРЕЖДЕНИЕ: obsolete voyahtune.setenforce.rc не удалён; повторный setenforce идемпотентен.
call :boot_hook_cleanup_snapshot
echo   Boot-hook установлен и проверен.
exit /b 0

:boot_hook_local_missing
echo !!! Неполный локальный комплект voyahtune.* - boot-hook не изменён.
exit /b 1

:boot_hook_stage_failed
call :boot_hook_cleanup_stage
echo !!! Не удалось подготовить boot-hook - рабочая версия сохранена.
exit /b 1

:boot_hook_snapshot_failed
call :boot_hook_cleanup_stage
call :boot_hook_cleanup_snapshot
echo !!! Не удалось сохранить текущий boot-hook - рабочая версия не изменена.
exit /b 1

:boot_hook_publish_failed
call :boot_hook_rollback
if errorlevel 1 (
    echo !!! Ошибка публикации и rollback boot-hook. Не перезагружайте ГУ; повторите установку.
    exit /b 2
) else (
    echo !!! Ошибка публикации boot-hook; предыдущая версия восстановлена.
)
exit /b 1

:boot_hook_verify_failed
call :boot_hook_rollback
if errorlevel 1 (
    echo !!! Проверка и rollback boot-hook не прошли. Не перезагружайте ГУ; повторите установку.
    exit /b 2
) else (
    echo !!! Проверка boot-hook не прошла; предыдущая версия восстановлена.
)
exit /b 1
