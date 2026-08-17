@echo off
cd /d "%~dp0" || exit /b 1

set "YDNS_HELPER=%~dp0dns-overlay.bat"
set "YDNS_STATUS_FILE=%TEMP%\open_voyah_ydns_%RANDOM%_%RANDOM%.tmp"

if not exist "%YDNS_HELPER%" (
    echo !!! Missing dns-overlay.bat. The device was not changed.
    exit /b 1
)

call "%YDNS_HELPER%" prepare-install
if errorlevel 1 (
    echo !!! The Yandex DNS installer package is incomplete. The device was not changed.
    exit /b 1
)

adb.exe root
if errorlevel 1 goto :adb_failed
adb.exe wait-for-device
if errorlevel 1 goto :adb_failed
adb.exe root
if errorlevel 1 goto :adb_failed

call "%YDNS_HELPER%" status > "%YDNS_STATUS_FILE%"
if errorlevel 1 goto :status_failed

findstr.exe /i /x /c:"on" "%YDNS_STATUS_FILE%" >nul
if not errorlevel 1 goto :install
findstr.exe /i /x /c:"off" "%YDNS_STATUS_FILE%" >nul
if not errorlevel 1 goto :install
findstr.exe /i /x /c:"external" "%YDNS_STATUS_FILE%" >nul
if not errorlevel 1 goto :external_overlay
findstr.exe /i /x /c:"broken" "%YDNS_STATUS_FILE%" >nul
if not errorlevel 1 goto :broken_overlay
goto :status_failed

:install
del "%YDNS_STATUS_FILE%" >nul 2>nul
call "%YDNS_HELPER%" install
if errorlevel 1 (
    echo !!! Yandex DNS installation failed. Fix the error and run this file again.
    exit /b 1
)
adb.exe reboot
if errorlevel 1 (
    echo !!! Yandex DNS was installed, but ADB could not reboot the device. Reboot it manually.
    exit /b 1
)
echo Yandex DNS installation complete. The device is rebooting.
exit /b 0

:external_overlay
del "%YDNS_STATUS_FILE%" >nul 2>nul
echo !!! A DNS overlay not managed by Open Voyah is already installed.
echo     It was left unchanged. Remove it manually before installing Yandex DNS.
exit /b 1

:broken_overlay
del "%YDNS_STATUS_FILE%" >nul 2>nul
echo !!! The existing DNS overlay state is inconsistent.
echo     Run remove.bat first, reboot the device, and try again.
exit /b 1

:status_failed
del "%YDNS_STATUS_FILE%" >nul 2>nul
echo !!! Could not determine the current DNS overlay state. The device was not changed.
exit /b 1

:adb_failed
del "%YDNS_STATUS_FILE%" >nul 2>nul
echo !!! ADB could not connect to the device with root access.
exit /b 1
