@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0" || exit /b 1
set "YDNS_HELPER=%~dp0dns-overlay.bat"
if not exist "%YDNS_HELPER%" (
    echo !!! Missing dns-overlay.bat. Removal stopped before changing the device.
    exit /b 1
)
call "%YDNS_HELPER%" prepare
if errorlevel 1 (
    echo !!! The DNS overlay helper is not ready. Removal stopped before changing the device.
    exit /b 1
)
if not exist "init.logcat.original.sh" (
    echo !!! Missing migration file init.logcat.original.sh. Removal stopped before changing the device.
    exit /b 1
)

adb.exe root >nul 2>nul
adb.exe wait-for-device
adb.exe root >nul 2>nul
adb.exe disable-verity >nul 2>nul
adb.exe remount >nul 2>nul
adb.exe shell "mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null"
adb.exe shell "touch /system/.ovw_remove_rwtest && rm -f /system/.ovw_remove_rwtest"
if errorlevel 1 (
    echo !!! /system is not writable. Removal stopped before changing components.
    exit /b 1
)

adb.exe shell settings put global open_voyah_apollo_master 0
if errorlevel 1 (
    echo !!! Could not disable Apollo master. Removal stopped before unloading the hook.
    exit /b 1
)
set APOLLO_MASTER_STATE=
for /f "delims=" %%i in ('adb.exe shell settings get global open_voyah_apollo_master 2^>nul') do set APOLLO_MASTER_STATE=%%i
if not "%APOLLO_MASTER_STATE%"=="0" (
    echo !!! Apollo master did not confirm value 0. Removal stopped before unloading the hook.
    exit /b 1
)
adb.exe shell settings put global open_voyah_apollo_legacy_hook_enabled 0
if errorlevel 1 (
    echo !!! Could not disable legacy Apollo opt-in. Removal stopped before unloading the hook.
    exit /b 1
)
set APOLLO_LEGACY_OPT_IN_STATE=
for /f "delims=" %%i in ('adb.exe shell settings get global open_voyah_apollo_legacy_hook_enabled 2^>nul') do set APOLLO_LEGACY_OPT_IN_STATE=%%i
if not "%APOLLO_LEGACY_OPT_IN_STATE%"=="0" (
    echo !!! Legacy Apollo opt-in did not confirm value 0. Removal stopped.
    exit /b 1
)
timeout /t 3 /nobreak >nul

echo === Restoring the DNS overlay ===
call "%YDNS_HELPER%" restore
if errorlevel 1 (
    echo !!! Could not restore or disable the DNS overlay. Other components were not removed.
    exit /b 1
)

echo === Migrating the previous full-release boot hook ===
call :migrate_legacy_init_logcat
if errorlevel 1 (
    echo !!! DNS was restored, but boot components were not removed. Fix the error and run remove again.
    exit /b 1
)

echo === Stopping and removing VoyahTune RC services ===
call :stop_voyahtune_load
if errorlevel 1 (
    call :rollback_legacy_init_logcat
    if errorlevel 1 echo !!! Legacy init.logcat.sh could not be restored. Do not reboot; run remove again.
    exit /b 1
)
adb.exe shell "rm -f /system/etc/init/voyahtune.load.rc /system/etc/init.voyahtune.load.sh /system/etc/init/voyahtune.load.sh /system/etc/init/voyahtune.setenforce.rc && test ! -e /system/etc/init/voyahtune.load.rc && test ! -e /system/etc/init.voyahtune.load.sh && test ! -e /system/etc/init/voyahtune.load.sh && test ! -e /system/etc/init/voyahtune.setenforce.rc"
if errorlevel 1 (
    echo !!! Could not remove VoyahTune RC files. Removal stopped.
    echo     Do not reboot. Restore ADB and run remove again.
    exit /b 1
)
set "LEGACY_INIT_MIGRATED=0"
adb.exe shell "rm -f /system/etc/.voyahtune.setenforce.rc.new /system/etc/.voyahtune.load.rc.new /system/etc/.voyahtune.load.sh.new /system/etc/.voyahtune.setenforce.rc.previous /system/etc/.voyahtune.setenforce.rc.absent /system/etc/.voyahtune.load.rc.previous /system/etc/.voyahtune.load.rc.absent /system/etc/.voyahtune.load.sh.previous /system/etc/.voyahtune.load.sh.absent /system/etc/.voyahtune.setenforce.rc.rollback /system/etc/.voyahtune.load.rc.rollback /system/etc/.voyahtune.load.sh.rollback /system/etc/init.logcat.sh.voyahtune.new /system/etc/init.logcat.sh.voyahtune.rollback"
if errorlevel 1 echo   WARNING: some inactive transaction files remain; the boot hook is already removed.

adb.exe shell "pkill -f /data/local/bin/load.bin"
adb.exe shell "rm -f /data/local/tmp/voyahtune_load.v2.lock /data/local/tmp/voyah_load.v2.lock"
adb.exe shell "rm -rf /data/local/tmp/voyah_load.lock"
adb.exe shell "ps -ef | grep frida-inject | grep -E 'vd_bypass|steeringwheelkeys|launcherdock|multidisplay|apollo_tech|keyboard_lock_en|keyboard_ru' | grep -v grep | awk '{print $2}' | xargs kill -9"
adb.exe shell "am force-stop com.qinggan.app.vehiclesetting"
adb.exe shell "am force-stop com.qinggan.app.qgime"

if exist "backup\load.bin" (
    adb.exe push backup\load.bin /data/local/bin/load.bin
) else (
    adb.exe shell "rm -f /data/local/bin/load.bin"
)
adb.exe shell "rm -f /data/local/bin/vd_bypass.js"
adb.exe shell "rm -f /data/local/bin/steeringwheelkeys.js /data/local/bin/launcherdock.js /data/local/bin/multidisplay.js /data/local/bin/keymng2.js"
rem Obsolete Apollo hook is never restored, including backups from old releases.
adb.exe shell "rm -f /data/local/bin/apollo_tech.js /data/local/bin/apollo_tech.js.new"
adb.exe shell "rm -f /data/local/bin/keyboard_lock_en.js /data/local/bin/keyboard_ru.js /data/local/bin/voyahtune_keyboard_en_config.json /data/local/bin/voyahtune_keyboard_ru_config.json /data/local/bin/voyahtune_skb_qwerty_ru.json /data/local/tmp/voyahtune_keyboard.pid /data/local/tmp/voyahtune_keyboard.attempt /data/local/tmp/voyahtune_keyboard.txt /data/local/tmp/voyahtune_keyboard.txt.try"
if exist "backup\frida-inject" (
    adb.exe push backup\frida-inject /data/local/bin/frida-inject
) else (
    adb.exe shell "rm -f /data/local/bin/frida-inject"
)
echo === Cleaning Open Voyah files ===
adb.exe shell "rm -f /data/local/bin/vd_bypass.js /data/local/bin/steeringwheelkeys.js /data/local/bin/launcherdock.js /data/local/bin/multidisplay.js /data/local/bin/keymng2.js /data/local/bin/apollo_tech.js /data/local/bin/apollo_tech.js.new /data/local/tmp/voyahtune_load.v2.lock /data/local/tmp/voyahtune_vd.pid /data/local/tmp/voyahtune_vd.attempt /data/local/tmp/voyahtune_swk_km.pid /data/local/tmp/voyahtune_swk_km.busy /data/local/tmp/voyahtune_swk_km.attempt /data/local/tmp/voyahtune_lnch.pid /data/local/tmp/voyahtune_lnch.attempt /data/local/tmp/voyahtune_md.pid /data/local/tmp/voyahtune_md.attempt /data/local/tmp/voyahtune_load.txt /data/local/tmp/voyahtune_vd_bypass.txt /data/local/tmp/voyahtune_vd_bypass.txt.try /data/local/tmp/voyahtune_swk.txt /data/local/tmp/voyahtune_swk.try /data/local/tmp/voyahtune_lnch.txt /data/local/tmp/voyahtune_lnch.txt.try /data/local/tmp/voyahtune_md.txt /data/local/tmp/voyahtune_md.txt.try /data/local/tmp/voyah_load.v2.lock /data/local/tmp/voyah_vd.pid /data/local/tmp/voyah_swk_ss.pid /data/local/tmp/voyah_swk_km.pid /data/local/tmp/voyah_swk_km.busy /data/local/tmp/voyah_km.pid /data/local/tmp/voyah_lnch.pid /data/local/tmp/voyah_md.pid /data/local/tmp/voyah_apollo.pid /data/local/tmp/voyah_apollo.down /data/local/tmp/voyah_apollo.disabled /data/local/tmp/voyah_load.txt /data/local/tmp/voyah_vd_bypass.txt /data/local/tmp/voyah_vd_bypass.txt.try /data/local/tmp/voyah_keymng.txt /data/local/tmp/voyah_swk.txt /data/local/tmp/voyah_swk.txt.try /data/local/tmp/voyah_lnch.txt /data/local/tmp/voyah_lnch.txt.try /data/local/tmp/voyah_md.txt /data/local/tmp/voyah_md.txt.try /data/local/tmp/voyah_apollo.txt /data/local/tmp/voyah_apollo.txt.1 /data/local/tmp/voyah_apollo.txt.try /data/local/tmp/open_voyah_dns_overlay.sh /data/local/tmp/open_voyah_yandex_dns.apk /sdcard/tmp/voyahtune_native_log.txt /sdcard/tmp/voyah_native_log.txt && rm -rf /data/local/tmp/voyah_load.lock /data/local/open_voyah && for path in /data/local/bin/vd_bypass.js /data/local/bin/steeringwheelkeys.js /data/local/bin/launcherdock.js /data/local/bin/multidisplay.js /data/local/bin/keymng2.js /data/local/bin/apollo_tech.js /data/local/bin/apollo_tech.js.new /data/local/tmp/voyahtune_load.v2.lock /data/local/tmp/voyahtune_vd.pid /data/local/tmp/voyahtune_vd.attempt /data/local/tmp/voyahtune_swk_km.pid /data/local/tmp/voyahtune_swk_km.busy /data/local/tmp/voyahtune_swk_km.attempt /data/local/tmp/voyahtune_lnch.pid /data/local/tmp/voyahtune_lnch.attempt /data/local/tmp/voyahtune_md.pid /data/local/tmp/voyahtune_md.attempt /data/local/tmp/voyahtune_load.txt /data/local/tmp/voyahtune_vd_bypass.txt /data/local/tmp/voyahtune_vd_bypass.txt.try /data/local/tmp/voyahtune_swk.txt /data/local/tmp/voyahtune_swk.try /data/local/tmp/voyahtune_lnch.txt /data/local/tmp/voyahtune_lnch.txt.try /data/local/tmp/voyahtune_md.txt /data/local/tmp/voyahtune_md.txt.try /data/local/tmp/voyah_load.v2.lock /data/local/tmp/voyah_load.lock /data/local/tmp/voyah_vd.pid /data/local/tmp/voyah_swk_ss.pid /data/local/tmp/voyah_swk_km.pid /data/local/tmp/voyah_swk_km.busy /data/local/tmp/voyah_km.pid /data/local/tmp/voyah_lnch.pid /data/local/tmp/voyah_md.pid /data/local/tmp/voyah_apollo.pid /data/local/tmp/voyah_apollo.down /data/local/tmp/voyah_apollo.disabled /data/local/tmp/voyah_load.txt /data/local/tmp/voyah_vd_bypass.txt /data/local/tmp/voyah_vd_bypass.txt.try /data/local/tmp/voyah_keymng.txt /data/local/tmp/voyah_swk.txt /data/local/tmp/voyah_swk.txt.try /data/local/tmp/voyah_lnch.txt /data/local/tmp/voyah_lnch.txt.try /data/local/tmp/voyah_md.txt /data/local/tmp/voyah_md.txt.try /data/local/tmp/voyah_apollo.txt /data/local/tmp/voyah_apollo.txt.1 /data/local/tmp/voyah_apollo.txt.try /data/local/tmp/open_voyah_dns_overlay.sh /data/local/tmp/open_voyah_yandex_dns.apk /data/local/open_voyah /sdcard/tmp/voyahtune_native_log.txt /sdcard/tmp/voyah_native_log.txt; do if [ -e \"$path\" ] || [ -L \"$path\" ]; then exit 1; fi; done"
adb.exe shell "rm -f /data/local/tmp/voyahtune_apollo.pid /data/local/tmp/voyahtune_apollo.attempt /data/local/tmp/voyahtune_apollo.txt /data/local/tmp/voyahtune_apollo.txt.try && for path in /data/local/tmp/voyahtune_apollo.pid /data/local/tmp/voyahtune_apollo.attempt /data/local/tmp/voyahtune_apollo.txt /data/local/tmp/voyahtune_apollo.txt.try; do if [ -e \"$path\" ] || [ -L \"$path\" ]; then exit 1; fi; done"
if errorlevel 1 exit /b 1
if errorlevel 1 (
    echo !!! Could not completely remove Open Voyah files. Reboot was cancelled.
    exit /b 1
)
echo === Cleaning Settings.Global ===
adb.exe shell "for setting_name in voyahtune_dock1 voyahtune_dock2 voyahtune_dock1Dpi voyahtune_dock2Dpi voyahtune_steerStarShort voyahtune_steerStarLong voyahtune_steerDvrShort voyahtune_steerDvrLong voyahtune_steerVoiceShort voyahtune_steerVoiceLong voyahtune_steerPhoneShort voyahtune_steerPhoneLong open_voyah_apollo_master open_voyah_apollo_legacy_hook_enabled open_voyah_apollo_asc open_voyah_apollo_sdb open_voyah_apollo_profile_supported open_voyah_apollo_profile_heartbeat voyahtune_keyboard_mode enable_freeform_support force_resizable_activities; do settings delete global $setting_name >/dev/null 2>&1 || exit 1; done"
if errorlevel 1 (
    echo !!! Could not completely clean Settings.Global. Reboot was cancelled.
    exit /b 1
)
echo   Open Voyah settings were cleaned.

echo === Removing Open Voyah APKs ===
adb.exe shell am force-stop ru.big.town.anative >nul 2>nul
adb.exe shell am force-stop ru.big.town.restoremode >nul 2>nul
rem Android 11 CE/DE and /data_mirror state must be removed by PackageManager/installd only.
adb.exe shell "pm uninstall ru.big.town.anative >/dev/null 2>&1 || true; pm uninstall --user 0 ru.big.town.anative >/dev/null 2>&1 || true; pm uninstall ru.big.town.restoremode >/dev/null 2>&1 || true; if pm path ru.big.town.anative 2>/dev/null | grep -q '^package:/data/app/'; then exit 1; fi; if pm path ru.big.town.restoremode 2>/dev/null | grep -q '^package:'; then exit 1; fi"
if errorlevel 1 (
    echo !!! Could not remove an Open Voyah data APK or update. Reboot was cancelled.
    exit /b 1
)
echo   PackageManager removed user data, RestoreMode, and any Native update.
adb.exe shell "rm -f /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml /system/etc/.privapp-permissions-ru.big.town.anative.xml.voyahtune.new /system/priv-app/.Native.apk.voyahtune.new && rm -rf /system/priv-app/Native && test ! -e /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml && test ! -e /system/etc/.privapp-permissions-ru.big.town.anative.xml.voyahtune.new && test ! -e /system/priv-app/.Native.apk.voyahtune.new && test ! -e /system/priv-app/Native"
if errorlevel 1 (
    echo !!! Could not completely remove Open Voyah system files. Reboot was cancelled.
    exit /b 1
)
adb.exe reboot
if errorlevel 1 (
    echo !!! Removal is prepared, but ADB could not reboot the device. Reboot it manually.
    exit /b 1
)
echo Removal complete. The device is rebooting.
exit /b 0

:migrate_legacy_init_logcat
set "LEGACY_INIT_STATE_FILE=%TEMP%\open_voyah_legacy_init_%RANDOM%_%RANDOM%.tmp"
set "LEGACY_INIT_ROLLBACK_SOURCE=backup\init.logcat.voyahtune-legacy.sh"
set "LEGACY_INIT_MIGRATED=0"
set "LEGACY_INIT_STATE="
adb.exe shell "if [ ! -e /system/etc/init.logcat.sh ]; then echo MISSING; elif [ ! -f /system/etc/init.logcat.sh ]; then echo ERROR; else grep -qF '# init.logcat.sh Open Voyah:' /system/etc/init.logcat.sh 2>/dev/null; legacy_grep_status=$?; if [ $legacy_grep_status -eq 0 ]; then echo LEGACY; elif [ $legacy_grep_status -eq 1 ]; then echo CLEAN; else echo ERROR; fi; fi" > "%LEGACY_INIT_STATE_FILE%" 2>nul
if errorlevel 1 goto :remove_legacy_init_check_failed
set /p "LEGACY_INIT_STATE=" < "%LEGACY_INIT_STATE_FILE%"
del "%LEGACY_INIT_STATE_FILE%" >nul 2>nul
if "%LEGACY_INIT_STATE%"=="CLEAN" (
    echo   Stock init.logcat.sh has no VoyahTune marker - left unchanged.
    exit /b 0
)
if "%LEGACY_INIT_STATE%"=="MISSING" (
    echo   /system/etc/init.logcat.sh is absent - no legacy hook restoration is needed.
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
if not exist "backup\init.logcat.sh" goto :remove_legacy_init_source_selected
call :validate_legacy_init_source "backup\init.logcat.sh"
if not "%LEGACY_INIT_VALID%"=="1" goto :remove_legacy_init_source_selected
set "LEGACY_INIT_SOURCE=backup\init.logcat.sh"
:remove_legacy_init_source_selected
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
if errorlevel 1 goto :remove_legacy_init_publish_failed
set "LEGACY_INIT_MIGRATED=1"
adb.exe shell "chown 0:0 /system/etc/init.logcat.sh.voyahtune.new && chmod 644 /system/etc/init.logcat.sh.voyahtune.new && /system/bin/sh -n /system/etc/init.logcat.sh.voyahtune.new && restorecon /system/etc/init.logcat.sh.voyahtune.new && mv -f /system/etc/init.logcat.sh.voyahtune.new /system/etc/init.logcat.sh && restorecon /system/etc/init.logcat.sh && sync"
if errorlevel 1 goto :remove_legacy_init_publish_failed

set "LEGACY_INIT_STATE="
adb.exe shell "if [ ! -e /system/etc/init.logcat.sh ]; then echo MISSING; elif [ ! -f /system/etc/init.logcat.sh ]; then echo ERROR; else grep -qF '# init.logcat.sh Open Voyah:' /system/etc/init.logcat.sh 2>/dev/null; legacy_grep_status=$?; if [ $legacy_grep_status -eq 0 ]; then echo LEGACY; elif [ $legacy_grep_status -eq 1 ]; then echo CLEAN; else echo ERROR; fi; fi" > "%LEGACY_INIT_STATE_FILE%" 2>nul
if errorlevel 1 goto :remove_legacy_init_check_failed
set /p "LEGACY_INIT_STATE=" < "%LEGACY_INIT_STATE_FILE%"
del "%LEGACY_INIT_STATE_FILE%" >nul 2>nul
if not "%LEGACY_INIT_STATE%"=="CLEAN" (
    call :rollback_legacy_init_logcat
    if errorlevel 1 echo !!! init.logcat.sh rollback was not confirmed. Do not reboot; run remove again.
    echo !!! The legacy marker remains after restoring init.logcat.sh.
    exit /b 1
)
echo   Legacy init.logcat.sh was removed from the boot path.
exit /b 0

:remove_legacy_init_check_failed
del "%LEGACY_INIT_STATE_FILE%" >nul 2>nul
call :rollback_legacy_init_logcat
if errorlevel 1 echo !!! init.logcat.sh rollback was not confirmed. Do not reboot; run remove again.
echo !!! Could not verify /system/etc/init.logcat.sh. Removal stopped.
exit /b 1

:remove_legacy_init_publish_failed
adb.exe shell "rm -f /system/etc/init.logcat.sh.voyahtune.new" >nul 2>nul
call :rollback_legacy_init_logcat
if errorlevel 1 echo !!! init.logcat.sh rollback was not confirmed. Do not reboot; run remove again.
echo !!! Could not atomically restore init.logcat.sh. Removal stopped.
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
exit /b 0

:stop_voyahtune_load
set "VTS_STATE_FILE=%TEMP%\open_voyah_svc_%RANDOM%_%RANDOM%.tmp"
set /a VTS_TRIES=0
call :read_voyahtune_state
if errorlevel 1 goto :stop_voyahtune_load_fail
if not defined VTS_STATE goto :stop_voyahtune_load_ok
if /i "%VTS_STATE%"=="stopped" goto :stop_voyahtune_load_ok
adb.exe shell "setprop ctl.stop voyahtune_load"
if errorlevel 1 goto :stop_voyahtune_load_fail

:stop_voyahtune_load_poll
call :read_voyahtune_state
if errorlevel 1 goto :stop_voyahtune_load_fail
if not defined VTS_STATE goto :stop_voyahtune_load_ok
if /i "%VTS_STATE%"=="stopped" goto :stop_voyahtune_load_ok
set /a VTS_TRIES+=1
if %VTS_TRIES% GEQ 20 goto :stop_voyahtune_load_fail
timeout /t 1 /nobreak >nul
goto :stop_voyahtune_load_poll

:read_voyahtune_state
set "VTS_STATE="
adb.exe shell getprop init.svc.voyahtune_load > "%VTS_STATE_FILE%" 2>nul
if errorlevel 1 exit /b 1
set /p "VTS_STATE=" < "%VTS_STATE_FILE%"
exit /b 0

:stop_voyahtune_load_ok
del "%VTS_STATE_FILE%" >nul 2>nul
echo   voyahtune_load is stopped or is not registered.
exit /b 0

:stop_voyahtune_load_fail
del "%VTS_STATE_FILE%" >nul 2>nul
echo !!! voyahtune_load did not stop. Boot file removal was cancelled.
exit /b 1
