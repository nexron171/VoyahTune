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

adb.exe root
adb.exe wait-for-device
adb.exe root
adb.exe disable-verity >nul 2>nul
adb.exe remount >nul 2>nul
adb.exe shell "mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null"

echo === Restoring the DNS overlay ===
call "%YDNS_HELPER%" restore
if errorlevel 1 (
    echo !!! Could not restore or disable the DNS overlay. Other components were not removed.
    exit /b 1
)

adb.exe shell "rm -f /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml"
adb.exe shell "rm -rf /system/priv-app/Native"
adb.exe shell "ls -all /system/priv-app/Native"
adb.exe shell pm uninstall ru.big.town.anative
adb.exe shell pm uninstall ru.big.town.restoremode
adb.exe shell am force-stop ru.big.town.anative
adb.exe shell "rm -rf /data/user/0/ru.big.town.anative /data/user_de/0/ru.big.town.anative /data/data/ru.big.town.anative"

adb.exe shell settings delete global open_voyah_apollo_master 2>nul
adb.exe shell settings delete global open_voyah_apollo_legacy_hook_enabled 2>nul
adb.exe shell settings delete global open_voyah_apollo_asc 2>nul
adb.exe shell settings delete global open_voyah_apollo_sdb 2>nul
adb.exe shell settings delete global open_voyah_apollo_profile_supported 2>nul
adb.exe shell settings delete global open_voyah_apollo_profile_heartbeat 2>nul


adb.exe reboot
if errorlevel 1 (
    echo !!! Removal is prepared, but ADB could not reboot the device. Reboot it manually.
    exit /b 1
)
echo Removal complete. The device is rebooting.
exit /b 0
