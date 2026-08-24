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

adb.exe root >nul 2>nul
adb.exe wait-for-device
adb.exe root >nul 2>nul
adb.exe disable-verity >nul 2>nul
adb.exe remount >nul 2>nul
adb.exe shell "mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null"

echo === Restoring the DNS overlay ===
call "%YDNS_HELPER%" restore
if errorlevel 1 (
    echo !!! Could not restore or disable the DNS overlay. Other components were not removed.
    exit /b 1
)

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
rem Light does not create full-hook files. Remove only Open Voyah helper/state/log paths;
rem PackageManager already owns and removes CE/DE, profiles, and Android/data.
adb.exe shell "rm -f /data/local/bin/apollo_tech.js /data/local/bin/apollo_tech.js.new /data/local/tmp/voyah_apollo.pid /data/local/tmp/voyah_apollo.down /data/local/tmp/voyah_apollo.disabled /data/local/tmp/voyah_apollo.txt /data/local/tmp/voyah_apollo.txt.1 /data/local/tmp/voyah_apollo.txt.try /data/local/tmp/open_voyah_dns_overlay.sh /data/local/tmp/open_voyah_yandex_dns.apk /sdcard/tmp/voyahtune_native_log.txt /sdcard/tmp/voyah_native_log.txt && rm -rf /data/local/open_voyah && for path in /data/local/bin/apollo_tech.js /data/local/bin/apollo_tech.js.new /data/local/tmp/voyah_apollo.pid /data/local/tmp/voyah_apollo.down /data/local/tmp/voyah_apollo.disabled /data/local/tmp/voyah_apollo.txt /data/local/tmp/voyah_apollo.txt.1 /data/local/tmp/voyah_apollo.txt.try /data/local/tmp/open_voyah_dns_overlay.sh /data/local/tmp/open_voyah_yandex_dns.apk /data/local/open_voyah /sdcard/tmp/voyahtune_native_log.txt /sdcard/tmp/voyah_native_log.txt; do if [ -e \"$path\" ] || [ -L \"$path\" ]; then exit 1; fi; done"
adb.exe shell "rm -f /data/local/tmp/voyahtune_apollo.pid /data/local/tmp/voyahtune_apollo.attempt /data/local/tmp/voyahtune_apollo.txt /data/local/tmp/voyahtune_apollo.txt.try && for path in /data/local/tmp/voyahtune_apollo.pid /data/local/tmp/voyahtune_apollo.attempt /data/local/tmp/voyahtune_apollo.txt /data/local/tmp/voyahtune_apollo.txt.try; do if [ -e \"$path\" ] || [ -L \"$path\" ]; then exit 1; fi; done"
if errorlevel 1 exit /b 1
if errorlevel 1 (
    echo !!! Could not completely remove Open Voyah helper/state files. Reboot was cancelled.
    exit /b 1
)

echo === Cleaning Settings.Global ===
adb.exe shell "for setting_name in open_voyah_apollo_master open_voyah_apollo_legacy_hook_enabled open_voyah_apollo_asc open_voyah_apollo_sdb open_voyah_apollo_profile_supported open_voyah_apollo_profile_heartbeat; do settings delete global $setting_name >/dev/null 2>&1 || exit 1; done"
if errorlevel 1 (
    echo !!! Could not completely clean Settings.Global. Reboot was cancelled.
    exit /b 1
)
echo   Open Voyah settings were cleaned.


adb.exe reboot
if errorlevel 1 (
    echo !!! Removal is prepared, but ADB could not reboot the device. Reboot it manually.
    exit /b 1
)
echo Removal complete. The device is rebooting.
exit /b 0
