@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0" || exit /b 1
for %%F in (adb.exe AdbWinApi.dll AdbWinUsbApi.dll native.apk restore_mode.apk privapp-permissions-ru.big.town.anative.xml) do if not exist "%%F" (
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
adb.exe shell "rm -f /data/local/bin/apollo_tech.js /data/local/bin/apollo_tech.js.new /data/local/tmp/voyahtune_apollo.pid /data/local/tmp/voyahtune_apollo.attempt /data/local/tmp/voyahtune_apollo.txt /data/local/tmp/voyahtune_apollo.txt.try /data/local/tmp/voyah_apollo.pid /data/local/tmp/voyah_apollo.down /data/local/tmp/voyah_apollo.disabled /data/local/tmp/voyah_apollo.txt /data/local/tmp/voyah_apollo.txt.1 /data/local/tmp/voyah_apollo.txt.try" 1>nul 2>nul
for %%K in (open_voyah_apollo_legacy_hook_enabled open_voyah_apollo_master open_voyah_apollo_asc open_voyah_apollo_sdb open_voyah_apollo_profile_supported open_voyah_apollo_profile_heartbeat) do adb.exe shell settings delete global %%K 1>nul 2>nul
echo   Apollo entitlement agent and markers removed. Light keeps read-only diagnostics only.

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
echo     Remove the incompatible package and repeat light install. /system is still unchanged.
exit /b 1
:canbus_permission_ok
if "%CANBUS_PERMISSION_PRESENT%"=="0" echo   The permission is not declared yet. Native will create it.
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
if not exist "%BACKUP_DIR%" mkdir "%BACKUP_DIR%"

echo === Backing up files to %BACKUP_DIR%\ ===
call :backup_pull /system/priv-app/Native/Native.apk     Native.apk
call :backup_pull /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml privapp-permissions-ru.big.town.anative.xml

echo === Native.apk in /system/priv-app ^(privileged permissions required for CAN features^) ===
adb.exe shell mkdir -p /system/priv-app/Native
adb.exe shell chmod 755 /system/priv-app/Native
adb.exe push native.apk /system/priv-app/Native/Native.apk
adb.exe shell "ls -all /system/priv-app/Native"

adb.exe shell "mkdir -p /system/etc/permissions"
adb.exe push privapp-permissions-ru.big.town.anative.xml /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml
adb.exe shell "chmod 644 /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml"

set LEAVECAR=
for /f "delims=" %%i in ('adb.exe shell getprop persist.app.feature.leavecar') do set LEAVECAR=%%i
if not "%LEAVECAR%"=="true" (
    echo Enabling leave car ^(power hold^)...
    adb.exe shell setprop persist.app.feature.leavecar true
)

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
call :wait_android_boot
if errorlevel 1 (
    echo !!! The head unit did not finish booting. Check ADB and run the installer again.
    exit /b 1
)
call :ensure_native_user_ready
if errorlevel 1 (
    echo !!! Files were installed, but the Native Android 11 package lifecycle was not restored.
    exit /b 1
)
echo Installation complete and verified.
echo Run install-yandex-dns.bat separately if Yandex DNS is required.
exit /b 0
goto :eof

:wait_android_boot
adb.exe wait-for-device
if errorlevel 1 exit /b 1
set /a NATIVE_BOOT_WAIT=0
:wait_android_boot_loop
adb.exe shell getprop sys.boot_completed 2>nul | findstr /b "1" >nul
if not errorlevel 1 goto :wait_android_boot_ready
set /a NATIVE_BOOT_WAIT+=1
if %NATIVE_BOOT_WAIT% GEQ 60 exit /b 1
timeout /t 5 /nobreak >nul
goto :wait_android_boot_loop
:wait_android_boot_ready
adb.exe root >nul 2>nul
if errorlevel 1 exit /b 1
adb.exe wait-for-device
if errorlevel 1 exit /b 1
adb.exe root >nul 2>nul
exit /b %errorlevel%

:native_user_data_ready
adb.exe shell "pm list packages --user 0 2>/dev/null | grep -qx 'package:ru.big.town.anative' && pm path ru.big.town.anative 2>/dev/null | grep -q '^package:' && test -d /data/user/0/ru.big.town.anative && test -d /data/user_de/0/ru.big.town.anative"
exit /b %errorlevel%

:ensure_native_user_ready
echo === Checking Native PackageManager data ^(Android 11 CE+DE^) ===
call :native_user_data_ready
if not errorlevel 1 goto :native_user_data_verified
echo   Native is not fully registered; repairing it through installd.
adb.exe shell "pm uninstall -k --user 0 ru.big.town.anative >/dev/null 2>&1 || true"
adb.exe shell "cmd package install-existing --user 0 --wait ru.big.town.anative"
if errorlevel 1 exit /b 1
call :native_user_data_ready
if errorlevel 1 exit /b 1
:native_user_data_verified
adb.exe shell "am broadcast -a com.qinggan.intent.QINGGAN_BOOT_COMPLETE -n ru.big.town.anative/.SetModesReceiverStatic >/dev/null"
if errorlevel 1 exit /b 1
set /a NATIVE_START_WAIT=0
:wait_native_process_loop
adb.exe shell pidof ru.big.town.anative 2>nul | findstr /r "[0-9]" >nul
if not errorlevel 1 (
    echo   Native started; CE/DE and process attach are verified.
    exit /b 0
)
set /a NATIVE_START_WAIT+=1
if %NATIVE_START_WAIT% GEQ 20 exit /b 1
timeout /t 1 /nobreak >nul
goto :wait_native_process_loop

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
    echo Backup: %BACKUP_DIR%\%~2 already exists - keeping the original
    exit /b 0
)
adb.exe pull %1 "%BACKUP_DIR%\%~2" 1>nul 2>nul
if errorlevel 1 (
    echo Backup: %1 does not exist - skipped
) else (
    echo Backup: %1 -^> %BACKUP_DIR%\%~2
)
exit /b 0
