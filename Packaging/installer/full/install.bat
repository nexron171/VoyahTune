@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0" || exit /b 1
for %%F in (adb.exe AdbWinApi.dll AdbWinUsbApi.dll load.bin steeringwheelkeys.js launcherdock.js multidisplay.js vd_bypass.js frida-inject-16.2.1-android-arm64 voyahtune.load.rc voyahtune.load.sh init.logcat.original.sh native.apk restore_mode.apk privapp-permissions-ru.big.town.anative.xml) do if not exist "%%F" (
    echo !!! Required file %%F is missing. The device was not changed.
    exit /b 1
)

adb.exe root
adb.exe wait-for-device
adb.exe root

echo === Removing old Apollo VehicleSetting hook ===
call :put_apollo_safe_key open_voyah_apollo_legacy_hook_enabled
if errorlevel 1 exit /b 1
call :put_apollo_safe_key open_voyah_apollo_master
if errorlevel 1 exit /b 1
call :put_apollo_safe_key open_voyah_apollo_profile_supported
if errorlevel 1 exit /b 1
call :put_apollo_safe_key open_voyah_apollo_profile_heartbeat
if errorlevel 1 exit /b 1
adb.exe shell am force-stop com.qinggan.app.vehiclesetting 1>nul 2>nul
adb.exe shell "rm -f /data/local/bin/apollo_tech.js /data/local/bin/apollo_tech.js.new /data/local/tmp/voyah_apollo.pid /data/local/tmp/voyah_apollo.down /data/local/tmp/voyah_apollo.disabled /data/local/tmp/voyah_apollo.txt /data/local/tmp/voyah_apollo.txt.1 /data/local/tmp/voyah_apollo.txt.try" 1>nul 2>nul
for %%K in (open_voyah_apollo_legacy_hook_enabled open_voyah_apollo_master open_voyah_apollo_asc open_voyah_apollo_sdb open_voyah_apollo_profile_supported open_voyah_apollo_profile_heartbeat) do adb.exe shell settings delete global %%K 1>nul 2>nul
echo   Old agent, markers, and keys removed. Apollo is Native-only.

echo === Preflight owner check for com.qinggan.permission.WRITE_CANBUS ===
adb.exe shell dumpsys package permissions >nul 2>nul
if errorlevel 1 (
    echo !!! PackageManager permissions are unavailable. Installation stopped before writing to /system.
    exit /b 1
)
set CANBUS_PERMISSION_PRESENT=0
adb.exe shell "dumpsys package permissions | grep -qF 'Permission [com.qinggan.permission.WRITE_CANBUS]'" >nul 2>nul
if not errorlevel 1 set CANBUS_PERMISSION_PRESENT=1
set CANBUS_PERMISSION_OWNER=
if "%CANBUS_PERMISSION_PRESENT%"=="1" for /f "tokens=2 delims==" %%i in ('adb.exe shell "dumpsys package permissions ^| grep -A 32 -F 'Permission [com.qinggan.permission.WRITE_CANBUS]' ^| grep -F 'sourcePackage='" 2^>nul') do if not defined CANBUS_PERMISSION_OWNER set CANBUS_PERMISSION_OWNER=%%i
if "%CANBUS_PERMISSION_PRESENT%"=="0" goto :canbus_permission_ok
if "%CANBUS_PERMISSION_OWNER%"=="ru.big.town.anative" goto :canbus_permission_ok
if "%CANBUS_PERMISSION_OWNER%"=="" (
    echo   WARNING: this firmware does not report the owner of com.qinggan.permission.WRITE_CANBUS.
    echo   Continuing. Android PackageManager will still reject a real duplicate permission.
    set CANBUS_PERMISSION_PRESENT=2
    goto :canbus_permission_ok
)
echo !!! com.qinggan.permission.WRITE_CANBUS already belongs to %CANBUS_PERMISSION_OWNER%.
echo     Remove the incompatible package and repeat full install. /system is still unchanged.
exit /b 1
:canbus_permission_ok
if "%CANBUS_PERMISSION_PRESENT%"=="0" echo   The permission is not declared yet. Full Native will create it.
if "%CANBUS_PERMISSION_PRESENT%"=="1" echo   The permission belongs to ru.big.town.anative. This update is compatible.

echo === Preparing writable /system ^(verity, overlay^) ===
adb.exe disable-verity
call :ensure_rw
if "%RWSTATE%"=="RW" goto :sys_rw_ok
echo   /system is read-only. Rebooting once to apply disable-verity...
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
echo !!! /system remains read-only. Installation stopped without changing /system.
echo     Possible causes: locked bootloader, EROFS firmware, or unsupported verity configuration.
echo     Try manually: adb disable-verity, adb reboot, adb root, adb remount.
exit /b 1
:sys_rw_ok
echo   /system is writable. Continuing.

set BACKUP_DIR=backup
if not exist "%BACKUP_DIR%" (
    mkdir "%BACKUP_DIR%"
    if errorlevel 1 (
        echo !!! Could not prepare %BACKUP_DIR%. Installation stopped before replacing files.
        exit /b 1
    )
)

echo === Backing up files to %BACKUP_DIR%\ ===
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
call :backup_pull /data/local/bin/frida-inject           frida-inject
if errorlevel 1 exit /b 1
call :backup_pull /system/priv-app/Native/Native.apk     Native.apk
if errorlevel 1 exit /b 1
call :backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml
if errorlevel 1 exit /b 1

echo === Frida infrastructure ^(steering wheel + VirtualDisplay^) ===
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
call :install_required_data_file frida-inject-16.2.1-android-arm64 /data/local/bin/frida-inject 755
if errorlevel 1 exit /b 1

echo === Migrating the previous full-release boot hook ===
call :migrate_legacy_init_logcat
if errorlevel 1 exit /b 1

echo === Boot hook: dedicated RC services ^(setenforce 0 + load.bin^) ===
call :install_boot_hooks
set "BOOT_HOOK_INSTALL_STATUS=%errorlevel%"
if "%BOOT_HOOK_INSTALL_STATUS%"=="0" goto :boot_hooks_installed
if "%BOOT_HOOK_INSTALL_STATUS%"=="1" call :handle_safe_boot_hook_failure
if "%BOOT_HOOK_INSTALL_STATUS%"=="2" echo !!! RC rollback was not confirmed. Clean init.logcat.sh was kept to avoid a second boot hook.
if "%BOOT_HOOK_INSTALL_STATUS%"=="2" echo     Do not reboot. Restore ADB and run the installer again.
exit /b 1
:boot_hooks_installed
set "LEGACY_INIT_MIGRATED=0"

echo === Native.apk in /system/priv-app ===
adb.exe shell "mkdir -p /system/priv-app/Native && chmod 755 /system/priv-app/Native"
if errorlevel 1 exit /b 1
call :install_required_system_file native.apk /system/priv-app/.Native.apk.voyahtune.new /system/priv-app/Native/Native.apk 644
if errorlevel 1 exit /b 1
adb.exe shell "ls -all /system/priv-app/Native"

adb.exe shell "mkdir -p /system/etc/permissions"
if errorlevel 1 exit /b 1
call :install_required_system_file privapp-permissions-ru.big.town.anative.xml /system/etc/.privapp-permissions-ru.big.town.anative.xml.voyahtune.new /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml 644
if errorlevel 1 exit /b 1

set LEAVECAR=
for /f "delims=" %%i in ('adb.exe shell getprop persist.app.feature.leavecar') do set LEAVECAR=%%i
if not "%LEAVECAR%"=="true" (
    echo Enabling leave car ^(power hold^)...
    adb.exe shell setprop persist.app.feature.leavecar true
)

adb.exe shell settings put global enable_freeform_support 1
adb.exe shell settings put global force_resizable_activities 1

adb.exe install -r -g restore_mode.apk
if errorlevel 1 (
    echo !!! RestoreMode was not installed. Fix the error and run the installer again before rebooting.
    exit /b 1
)

adb.exe reboot
if errorlevel 1 (
    echo !!! The installation is prepared, but ADB could not reboot the device. Reboot it manually.
    exit /b 1
)
echo Installation complete.
echo Run install-yandex-dns.bat separately if Yandex DNS is required.
exit /b 0
goto :eof

:put_apollo_safe_key
adb.exe shell settings put global %~1 0
if errorlevel 1 (
    echo !!! Could not write %~1=0. Installation stopped before writing to /system.
    exit /b 1
)
set "APOLLO_SAFE_STATE="
for /f "delims=" %%i in ('adb.exe shell settings get global %~1 2^>nul') do set "APOLLO_SAFE_STATE=%%i"
if not "%APOLLO_SAFE_STATE%"=="0" (
    echo !!! %~1 did not confirm value 0. Installation stopped before writing to /system.
    exit /b 1
)
exit /b 0

:ensure_rw
adb.exe remount >nul 2>nul
adb.exe shell "mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null" >nul 2>nul
echo rwtest> "%TEMP%\_ovw_rwtest.tmp"
adb.exe push "%TEMP%\_ovw_rwtest.tmp" /system/_ovw_rwtest >nul 2>nul
if errorlevel 1 (set RWSTATE=RO) else (set RWSTATE=RW)
adb.exe shell "rm -f /system/_ovw_rwtest" >nul 2>nul
del "%TEMP%\_ovw_rwtest.tmp" >nul 2>nul
exit /b 0

:backup_pull
if exist "%BACKUP_DIR%\%~2" (
    for %%A in ("%BACKUP_DIR%\%~2") do if %%~zA LEQ 0 (
        echo !!! Existing backup %BACKUP_DIR%\%~2 is empty or is not a file.
        exit /b 1
    )
    echo Backup: %BACKUP_DIR%\%~2 already exists - keeping the original
    exit /b 0
)
set "BACKUP_REMOTE_STATE="
for /f "delims=" %%i in ('adb.exe shell "if [ -f %1 ]; then echo PRESENT; elif [ -e %1 ]; then echo ERROR; else echo ABSENT; fi" 2^>nul') do set "BACKUP_REMOTE_STATE=%%i"
if "%BACKUP_REMOTE_STATE%"=="ABSENT" (
    echo Backup: %1 does not exist - skipped
    exit /b 0
)
if not "%BACKUP_REMOTE_STATE%"=="PRESENT" (
    echo !!! Could not safely read %1 before backup.
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
echo !!! Could not save existing %1. Installation stopped.
exit /b 1

:backup_pull_with_absent
if exist "%BACKUP_DIR%\%~2" (
    for %%A in ("%BACKUP_DIR%\%~2") do if %%~zA LEQ 0 (
        echo !!! Existing backup %BACKUP_DIR%\%~2 is empty or is not a file.
        exit /b 1
    )
    echo Backup: original state of %~2 is already saved - skipped
    exit /b 0
)
if exist "%BACKUP_DIR%\%~2.absent" (
    if exist "%BACKUP_DIR%\%~2.absent\NUL" (
        echo !!! Marker %BACKUP_DIR%\%~2.absent is not a file.
        exit /b 1
    )
    echo Backup: original state of %~2 is already saved - skipped
    exit /b 0
)
set APOLLO_REMOTE_STATE=
for /f "delims=" %%i in ('adb.exe shell "if [ -e %1 ]; then echo PRESENT; else echo ABSENT; fi" 2^>nul') do set APOLLO_REMOTE_STATE=%%i
if "%APOLLO_REMOTE_STATE%"=="ABSENT" (
    type nul > "%BACKUP_DIR%\%~2.absent"
    if errorlevel 1 (
        echo !!! Could not create %BACKUP_DIR%\%~2.absent
        exit /b 1
    )
    echo Backup: %1 was originally absent -^> %BACKUP_DIR%\%~2.absent
    exit /b 0
)
if not "%APOLLO_REMOTE_STATE%"=="PRESENT" (
    echo !!! Could not determine the original state of %1
    exit /b 1
)
del "%BACKUP_DIR%\%~2.new" 1>nul 2>nul
adb.exe pull %1 "%BACKUP_DIR%\%~2.new" 1>nul 2>nul
if errorlevel 1 (
    del "%BACKUP_DIR%\%~2.new" 1>nul 2>nul
    echo !!! Could not save existing %1
    exit /b 1
)
move /y "%BACKUP_DIR%\%~2.new" "%BACKUP_DIR%\%~2" 1>nul 2>nul
if errorlevel 1 (
    del "%BACKUP_DIR%\%~2.new" 1>nul 2>nul
    echo !!! Could not atomically commit the backup of existing %1
    exit /b 1
)
echo Backup: %1 -^> %BACKUP_DIR%\%~2
exit /b 0

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
    echo   Stock init.logcat.sh has no VoyahTune marker - left unchanged.
    exit /b 0
)
if "%LEGACY_INIT_STATE%"=="MISSING" (
    echo   /system/etc/init.logcat.sh is absent - no legacy hook migration is needed.
    exit /b 0
)
if not "%LEGACY_INIT_STATE%"=="LEGACY" (
    echo !!! Unknown init.logcat.sh check result: %LEGACY_INIT_STATE%
    exit /b 1
)

if not exist "backup" mkdir "backup"
del "%LEGACY_INIT_ROLLBACK_SOURCE%.new" >nul 2>nul
adb.exe pull /system/etc/init.logcat.sh "%LEGACY_INIT_ROLLBACK_SOURCE%.new" >nul 2>nul
if errorlevel 1 (
    echo !!! Could not save legacy init.logcat.sh for rollback.
    exit /b 1
)
for %%A in ("%LEGACY_INIT_ROLLBACK_SOURCE%.new") do if %%~zA LEQ 0 (
    del "%LEGACY_INIT_ROLLBACK_SOURCE%.new" >nul 2>nul
    echo !!! The legacy init.logcat.sh rollback copy is empty.
    exit /b 1
)
findstr /L /C:"# init.logcat.sh Open Voyah:" "%LEGACY_INIT_ROLLBACK_SOURCE%.new" >nul 2>nul
if errorlevel 1 (
    del "%LEGACY_INIT_ROLLBACK_SOURCE%.new" >nul 2>nul
    echo !!! The legacy init.logcat.sh rollback copy failed validation.
    exit /b 1
)
move /y "%LEGACY_INIT_ROLLBACK_SOURCE%.new" "%LEGACY_INIT_ROLLBACK_SOURCE%" >nul 2>nul
if errorlevel 1 (
    del "%LEGACY_INIT_ROLLBACK_SOURCE%.new" >nul 2>nul
    echo !!! Could not commit the legacy init.logcat.sh rollback copy.
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
    echo !!! Legacy init.logcat.sh was found, but %LEGACY_INIT_SOURCE% failed validation.
    exit /b 1
)
if "%LEGACY_INIT_SOURCE%"=="backup\init.logcat.sh" (
    echo   Found the previous installer's OEM backup: %LEGACY_INIT_SOURCE%
) else (
    echo   Using validated clean init.logcat.original.sh.
)

adb.exe push "%LEGACY_INIT_SOURCE%" /system/etc/init.logcat.sh.voyahtune.new
if errorlevel 1 goto :legacy_init_publish_failed
set "LEGACY_INIT_MIGRATED=1"
adb.exe shell "chown 0:0 /system/etc/init.logcat.sh.voyahtune.new && chmod 644 /system/etc/init.logcat.sh.voyahtune.new && /system/bin/sh -n /system/etc/init.logcat.sh.voyahtune.new && restorecon /system/etc/init.logcat.sh.voyahtune.new && mv -f /system/etc/init.logcat.sh.voyahtune.new /system/etc/init.logcat.sh && restorecon /system/etc/init.logcat.sh && sync"
if errorlevel 1 (
    call :rollback_legacy_init_logcat
    if errorlevel 1 echo !!! init.logcat.sh rollback was not confirmed. Do not reboot; run the installer again.
    goto :legacy_init_publish_failed
)

set "LEGACY_INIT_STATE="
adb.exe shell "if [ ! -e /system/etc/init.logcat.sh ]; then echo MISSING; elif [ ! -f /system/etc/init.logcat.sh ]; then echo ERROR; else grep -qF '# init.logcat.sh Open Voyah:' /system/etc/init.logcat.sh 2>/dev/null; legacy_grep_status=$?; if [ $legacy_grep_status -eq 0 ]; then echo LEGACY; elif [ $legacy_grep_status -eq 1 ]; then echo CLEAN; else echo ERROR; fi; fi" > "%LEGACY_INIT_STATE_FILE%" 2>nul
if errorlevel 1 goto :legacy_init_check_failed
set /p "LEGACY_INIT_STATE=" < "%LEGACY_INIT_STATE_FILE%"
del "%LEGACY_INIT_STATE_FILE%" >nul 2>nul
if not "%LEGACY_INIT_STATE%"=="CLEAN" (
    call :rollback_legacy_init_logcat
    if errorlevel 1 echo !!! init.logcat.sh rollback was not confirmed. Do not reboot; run the installer again.
    echo !!! The legacy marker remains after restoring init.logcat.sh.
    exit /b 1
)
echo   Legacy init.logcat.sh was removed from the boot path.
exit /b 0

:legacy_init_check_failed
del "%LEGACY_INIT_STATE_FILE%" >nul 2>nul
call :rollback_legacy_init_logcat
if errorlevel 1 echo !!! init.logcat.sh rollback was not confirmed. Do not reboot; run the installer again.
echo !!! Could not verify /system/etc/init.logcat.sh. Installation stopped.
exit /b 1

:legacy_init_publish_failed
adb.exe shell "rm -f /system/etc/init.logcat.sh.voyahtune.new" >nul 2>nul
echo !!! Could not atomically restore init.logcat.sh. Installation stopped.
exit /b 1

:validate_legacy_init_source
set "LEGACY_INIT_VALID=0"
if not exist "%~1" exit /b 0
for %%A in ("%~1") do if %%~zA LEQ 0 exit /b 0
findstr /B /L /C:"#!/system/bin/sh" "%~1" >nul 2>nul
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
echo   Legacy init.logcat.sh was restored after the failed RC installation.
exit /b 0

:install_required_data_file
adb.exe push "%~1" "%~2.voyahtune.new"
if errorlevel 1 goto :install_required_data_file_failed
adb.exe shell "chown 0:0 '%~2.voyahtune.new' && chmod '%~3' '%~2.voyahtune.new' && mv -f '%~2.voyahtune.new' '%~2' && test -f '%~2'"
if errorlevel 1 goto :install_required_data_file_failed
exit /b 0

:install_required_data_file_failed
adb.exe shell "rm -f '%~2.voyahtune.new'" >nul 2>nul
echo !!! Could not atomically install %~2. Installation stopped.
exit /b 1

:install_required_system_file
adb.exe push "%~1" "%~2"
if errorlevel 1 goto :install_required_system_file_failed
adb.exe shell "chown 0:0 '%~2' && chmod '%~4' '%~2' && restorecon '%~2' && mv -f '%~2' '%~3' && restorecon '%~3' && sync && test -f '%~3'"
if errorlevel 1 goto :install_required_system_file_failed
exit /b 0

:install_required_system_file_failed
adb.exe shell "rm -f '%~2'" >nul 2>nul
echo !!! Could not atomically install %~3. Installation stopped.
exit /b 1

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
    echo !!! Could not verify RC after rollback. The legacy hook was not restored to avoid two boot paths.
    echo     Do not reboot. Restore ADB and run the installer again.
    exit /b 0
)
if "%BOOT_HOOK_FINAL_STATE%"=="READY" (
    echo   The previous complete RC set was preserved. Legacy init.logcat.sh was not restored.
    exit /b 0
)
if "%BOOT_HOOK_FINAL_STATE%"=="PARTIAL" (
    echo !!! An incomplete RC set remains after rollback. The legacy hook was not restored to avoid two boot paths.
    echo     Do not reboot. Run the installer again.
    exit /b 0
)
call :rollback_legacy_init_logcat
if errorlevel 1 echo !!! Neither the new RC nor legacy rollback was installed. Do not reboot; run the installer again.
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
    echo !!! Could not prepare /system/etc/init. The boot hook was not installed.
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
if errorlevel 1 echo   WARNING: obsolete voyahtune.setenforce.rc was not removed; repeated setenforce is idempotent.
call :boot_hook_cleanup_snapshot
echo   Boot hook installed and verified.
exit /b 0

:boot_hook_local_missing
echo !!! The local voyahtune.* set is incomplete. The boot hook was not changed.
exit /b 1

:boot_hook_stage_failed
call :boot_hook_cleanup_stage
echo !!! Could not prepare the boot hook. The working version was preserved.
exit /b 1

:boot_hook_snapshot_failed
call :boot_hook_cleanup_stage
call :boot_hook_cleanup_snapshot
echo !!! Could not save the current boot hook. The working version was not changed.
exit /b 1

:boot_hook_publish_failed
call :boot_hook_rollback
if errorlevel 1 (
    echo !!! Boot hook publish and rollback both failed. Do not reboot; run the installer again.
    exit /b 2
) else (
    echo !!! Boot hook publish failed. The previous version was restored.
)
exit /b 1

:boot_hook_verify_failed
call :boot_hook_rollback
if errorlevel 1 (
    echo !!! Boot hook verification and rollback both failed. Do not reboot; run the installer again.
    exit /b 2
) else (
    echo !!! Boot hook verification failed. The previous version was restored.
)
exit /b 1
