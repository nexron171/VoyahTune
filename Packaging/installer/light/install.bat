@echo off
cd /d "%~dp0" || exit /b 1
for %%F in (adb.exe AdbWinApi.dll AdbWinUsbApi.dll native.apk restore_mode.apk privapp-permissions-ru.big.town.anative.xml) do if not exist "%%F" (
    echo !!! Required file %%F is missing. The device was not changed.
    exit /b 1
)

adb.exe root
adb.exe wait-for-device
adb.exe root

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
echo Installation complete.
echo Run install-yandex-dns.bat separately if Yandex DNS is required.
exit /b 0
goto :eof

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
