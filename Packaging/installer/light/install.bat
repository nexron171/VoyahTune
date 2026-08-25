@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "LIGHT_HOOK_BARRIER_PHASE=0"
call :install_main
set "LIGHT_INSTALL_RESULT=%ERRORLEVEL%"
if "%LIGHT_HOOK_BARRIER_PHASE%"=="1" if not "%LIGHT_INSTALL_RESULT%"=="0" (
    echo   WARNING: Light install stopped before teardown; trying to restart voyahtune_load.
    adb.exe shell "setprop ctl.start voyahtune_load 2>/dev/null || true" 1>nul 2>nul
)
if "%LIGHT_HOOK_BARRIER_PHASE%"=="2" if not "%LIGHT_INSTALL_RESULT%"=="0" echo   WARNING: teardown was left fail-closed; hook-loader is not restarted.
exit /b %LIGHT_INSTALL_RESULT%

:install_main
cd /d "%~dp0" || exit /b 1
for %%F in (adb.exe AdbWinApi.dll AdbWinUsbApi.dll native.apk restore_mode.apk privapp-permissions-ru.big.town.anative.xml) do if not exist "%%F" (
    echo !!! Required file %%F is missing. The device was not changed.
    exit /b 1
)

adb.exe root
adb.exe wait-for-device
adb.exe root

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

set "LEGACY_FULL_BOOT_STATE="
for /f "delims=" %%i in ('adb.exe shell "if [ ! -e /system/etc/init.logcat.sh ]; then echo CLEAN; elif [ ! -f /system/etc/init.logcat.sh ]; then echo ERROR; else grep -qF '# init.logcat.sh Open Voyah:' /system/etc/init.logcat.sh 2>/dev/null; legacy_grep_status=$?; if [ $legacy_grep_status -eq 0 ]; then echo LEGACY; elif [ $legacy_grep_status -eq 1 ]; then echo CLEAN; else echo ERROR; fi; fi" 2^>nul') do set "LEGACY_FULL_BOOT_STATE=%%i"
if not "%LEGACY_FULL_BOOT_STATE%"=="CLEAN" (
    echo !!! A legacy Full hook was found in init.logcat.sh.
    echo     Run full remove first, then run Light install; Light will not rewrite an unknown OEM file.
    exit /b 1
)

echo === Full to Light: stopping root hook runtime ===
set "LIGHT_HOOK_BARRIER_PHASE=1"
call :stop_full_hook_runtime_for_light
if errorlevel 1 (
    echo WARNING: init did not confirm Full hook-loader stop. Continuing teardown and mandatory reboot.
)

call :put_apollo_safe_key open_voyah_apollo_legacy_hook_enabled
if errorlevel 1 exit /b 1
call :put_apollo_safe_key open_voyah_apollo_master
if errorlevel 1 exit /b 1
call :put_apollo_safe_key open_voyah_apollo_profile_supported
if errorlevel 1 exit /b 1
call :put_apollo_safe_key open_voyah_apollo_profile_heartbeat
if errorlevel 1 exit /b 1
adb.exe shell am force-stop com.qinggan.app.vehiclesetting 1>nul 2>nul
adb.exe shell am force-stop com.qinggan.app.qgime 1>nul 2>nul

set "LIGHT_HOOK_BARRIER_PHASE=2"
call :remove_full_hook_runtime_for_light
if errorlevel 1 (
    echo !!! Full hook runtime was not removed completely. Do not reboot; run Light install again.
    exit /b 1
)
for %%K in (open_voyah_apollo_legacy_hook_enabled open_voyah_apollo_master open_voyah_apollo_asc open_voyah_apollo_sdb open_voyah_apollo_profile_supported open_voyah_apollo_profile_heartbeat voyahtune_keyboard_mode) do adb.exe shell settings delete global %%K 1>nul 2>nul
echo   Full boot path, project Frida scripts and runtime markers were removed.

echo === Native.apk in /system/priv-app ^(privileged permissions required for CAN features^) ===
adb.exe shell "mkdir -p /system/priv-app/Native && chown 0:0 /system/priv-app/Native && chmod 755 /system/priv-app/Native && restorecon /system/priv-app/Native"
if errorlevel 1 (
    echo !!! Could not prepare /system/priv-app/Native. Final reboot was cancelled.
    exit /b 1
)
call :install_required_system_file native.apk /system/priv-app/.Native.apk.voyahtune.new /system/priv-app/Native/Native.apk 644
if errorlevel 1 exit /b 1

adb.exe shell "mkdir -p /system/etc/permissions && chown 0:0 /system/etc/permissions && chmod 755 /system/etc/permissions && restorecon /system/etc/permissions"
if errorlevel 1 (
    echo !!! Could not prepare /system/etc/permissions. Final reboot was cancelled.
    exit /b 1
)
call :install_required_system_file privapp-permissions-ru.big.town.anative.xml /system/etc/.privapp-permissions-ru.big.town.anative.xml.voyahtune.new /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml 644
if errorlevel 1 exit /b 1
set "LIGHT_HOOK_BARRIER_PHASE=0"

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

:stop_full_hook_runtime_for_light
adb.exe shell "setprop ctl.stop voyahtune_load 2>/dev/null || exit 1; stop_wait=0; hook_state=$(getprop init.svc.voyahtune_load); while [ x$hook_state != xstopped ]; do if [ $stop_wait -ge 5 ]; then exit 1; fi; sleep 1; stop_wait=$((stop_wait + 1)); hook_state=$(getprop init.svc.voyahtune_load); done"
exit /b %ERRORLEVEL%

:remove_full_hook_runtime_for_light
adb.exe shell "ACTIVE_RC=/system/etc/init/voyahtune.load.rc; DISABLED_RC=/system/etc/init/voyahtune.load.rc.voyahtune-light-disabled; rm -f $DISABLED_RC || exit 1; if [ -e $ACTIVE_RC ] || [ -L $ACTIVE_RC ]; then mv -f $ACTIVE_RC $DISABLED_RC || exit 1; fi; [ ! -e $ACTIVE_RC ] && [ ! -L $ACTIVE_RC ] || exit 1; rm -f /system/etc/init.voyahtune.load.sh /system/etc/init/voyahtune.load.sh /system/etc/init/voyahtune.setenforce.rc /system/etc/.voyahtune.load.sh.new /system/etc/.voyahtune.load.sh.previous /system/etc/.voyahtune.load.sh.absent /system/etc/.voyahtune.load.sh.rollback /system/etc/.voyahtune.load.rc.new /system/etc/.voyahtune.load.rc.previous /system/etc/.voyahtune.load.rc.absent /system/etc/.voyahtune.load.rc.rollback /system/etc/.voyahtune.setenforce.rc.new /system/etc/.voyahtune.setenforce.rc.previous /system/etc/.voyahtune.setenforce.rc.absent /system/etc/.voyahtune.setenforce.rc.rollback $DISABLED_RC || exit 1"
if errorlevel 1 exit /b 1
adb.exe shell "if [ -f /data/local/bin/load.bin ] && grep -qF 'LOG_TAG=\"vt_load_bin\"' /data/local/bin/load.bin 2>/dev/null && grep -qF 'HOOK_MANIFEST=/data/local/bin/voyahtune-hook-manifest.json' /data/local/bin/load.bin 2>/dev/null; then rm -f /data/local/bin/load.bin || exit 1; fi; rm -f /data/local/bin/load.bin.voyahtune.new /data/local/bin/frida-inject.voyahtune.new"
if errorlevel 1 exit /b 1
adb.exe shell "rm -f /data/local/bin/apollo_tech.js /data/local/bin/apollo_tech.js.new /data/local/bin/apollo_tech.js.voyahtune.new /data/local/bin/vd_bypass.js /data/local/bin/vd_bypass.js.voyahtune.new /data/local/bin/steeringwheelkeys.js /data/local/bin/steeringwheelkeys.js.voyahtune.new /data/local/bin/launcherdock.js /data/local/bin/launcherdock.js.voyahtune.new /data/local/bin/multidisplay.js /data/local/bin/multidisplay.js.voyahtune.new /data/local/bin/keymng2.js /data/local/bin/keyboard_lock_en.js /data/local/bin/keyboard_lock_en.js.voyahtune.new /data/local/bin/keyboard_ru.js /data/local/bin/keyboard_ru.js.voyahtune.new /data/local/bin/voyahtune_keyboard_en_config.json /data/local/bin/voyahtune_keyboard_en_config.json.voyahtune.new /data/local/bin/voyahtune_keyboard_ru_config.json /data/local/bin/voyahtune_keyboard_ru_config.json.voyahtune.new /data/local/bin/voyahtune_skb_qwerty_ru.json /data/local/bin/voyahtune_skb_qwerty_ru.json.voyahtune.new /data/local/bin/voyahtune-hook-manifest.json /data/local/bin/voyahtune-hook-manifest.json.voyahtune.new"
if errorlevel 1 exit /b 1
adb.exe shell "rm -f /data/local/tmp/voyahtune_load.v2.lock /data/local/tmp/voyah_load.v2.lock /data/local/tmp/voyahtune_vd.pid /data/local/tmp/voyahtune_vd.attempt /data/local/tmp/voyahtune_vd_bypass.txt /data/local/tmp/voyahtune_vd_bypass.txt.try /data/local/tmp/voyahtune_swk_km.pid /data/local/tmp/voyahtune_swk_km.busy /data/local/tmp/voyahtune_swk_km.attempt /data/local/tmp/voyahtune_swk.txt /data/local/tmp/voyahtune_swk.try /data/local/tmp/voyahtune_lnch.pid /data/local/tmp/voyahtune_lnch.attempt /data/local/tmp/voyahtune_lnch.txt /data/local/tmp/voyahtune_lnch.txt.try /data/local/tmp/voyahtune_md.pid /data/local/tmp/voyahtune_md.attempt /data/local/tmp/voyahtune_md.txt /data/local/tmp/voyahtune_md.txt.try /data/local/tmp/voyahtune_apollo.pid /data/local/tmp/voyahtune_apollo.attempt /data/local/tmp/voyahtune_apollo.txt /data/local/tmp/voyahtune_apollo.txt.try /data/local/tmp/voyahtune_keyboard.pid /data/local/tmp/voyahtune_keyboard.attempt /data/local/tmp/voyahtune_keyboard.txt /data/local/tmp/voyahtune_keyboard.txt.try /data/local/tmp/voyahtune_load.txt /data/local/tmp/voyahtune-hook-status.v1 /data/local/tmp/voyahtune-hook-status.v1.*.new"
if errorlevel 1 exit /b 1
adb.exe shell "rm -f /data/local/tmp/voyah_vd.pid /data/local/tmp/voyah_swk_ss.pid /data/local/tmp/voyah_swk_km.pid /data/local/tmp/voyah_swk_km.busy /data/local/tmp/voyah_km.pid /data/local/tmp/voyah_lnch.pid /data/local/tmp/voyah_md.pid /data/local/tmp/voyah_apollo.pid /data/local/tmp/voyah_apollo.down /data/local/tmp/voyah_apollo.disabled /data/local/tmp/voyah_load.txt /data/local/tmp/voyah_vd_bypass.txt /data/local/tmp/voyah_vd_bypass.txt.try /data/local/tmp/voyah_keymng.txt /data/local/tmp/voyah_swk.txt /data/local/tmp/voyah_swk.txt.try /data/local/tmp/voyah_lnch.txt /data/local/tmp/voyah_lnch.txt.try /data/local/tmp/voyah_md.txt /data/local/tmp/voyah_md.txt.try /data/local/tmp/voyah_apollo.txt /data/local/tmp/voyah_apollo.txt.1 /data/local/tmp/voyah_apollo.txt.try; rm -rf /data/local/tmp/voyah_load.lock"
if errorlevel 1 exit /b 1
adb.exe shell "for removed_path in /system/etc/init/voyahtune.load.rc /system/etc/init.voyahtune.load.sh /system/etc/init/voyahtune.load.sh /data/local/bin/vd_bypass.js /data/local/bin/steeringwheelkeys.js /data/local/bin/launcherdock.js /data/local/bin/multidisplay.js /data/local/bin/apollo_tech.js /data/local/bin/keyboard_lock_en.js /data/local/bin/keyboard_ru.js /data/local/bin/voyahtune-hook-manifest.json /data/local/tmp/voyahtune-hook-status.v1; do [ ! -e $removed_path ] && [ ! -L $removed_path ] || exit 1; done; sync"
exit /b %ERRORLEVEL%

:install_required_system_file
adb.exe push "%~1" "%~2"
if errorlevel 1 goto :install_required_system_file_failed
adb.exe shell "chown 0:0 '%~2' && chmod '%~4' '%~2' && restorecon '%~2' && mv -f '%~2' '%~3' && restorecon '%~3' && sync && test -f '%~3'"
if errorlevel 1 goto :install_required_system_file_failed
exit /b 0

:install_required_system_file_failed
adb.exe shell "rm -f '%~2'" 1>nul 2>nul
echo !!! Could not atomically install %~3. Final reboot was cancelled.
exit /b 1

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
