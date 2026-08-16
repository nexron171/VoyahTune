@echo off
REM Windows host-side integration for the optional static Yandex DNS RRO.

set "YDNS_MODE=%~1"
set "YDNS_DIR=%~dp0"
set "YDNS_ADB=%YDNS_DIR%adb.exe"
set "YDNS_DEVICE_HELPER=%YDNS_DIR%dns-overlay-device.sh"
set "YDNS_APK=%YDNS_DIR%framework-res__config_ethernet_interfaces_yandexdns.apk"
set "YDNS_REMOTE_HELPER=/data/local/tmp/open_voyah_dns_overlay.sh"
set "YDNS_REMOTE_APK=/data/local/tmp/open_voyah_yandex_dns.apk"
set "YDNS_EXPECTED_SHA256=c4694866ff920b2409ce58d3dd4c84b86ba102049b68d27a6998ef91d7a0308d"

if /i "%YDNS_MODE%"=="prepare" goto :prepare
if /i "%YDNS_MODE%"=="prepare-install" goto :prepare_install
if /i "%YDNS_MODE%"=="status" goto :status
if /i "%YDNS_MODE%"=="install" goto :install
if /i "%YDNS_MODE%"=="disable" goto :disable
if /i "%YDNS_MODE%"=="restore" goto :restore
echo Использование: dns-overlay.bat ^{prepare^|prepare-install^|status^|install^|disable^|restore^} 1>&2
exit /b 2

:check_common
if not exist "%YDNS_ADB%" (
    echo !!! Не найден %YDNS_ADB% 1>&2
    exit /b 1
)
if not exist "%YDNS_DEVICE_HELPER%" (
    echo !!! Не найден %YDNS_DEVICE_HELPER% 1>&2
    exit /b 1
)
exit /b 0

:prepare
call :check_common
exit /b %errorlevel%

:prepare_install
call :check_common
if errorlevel 1 exit /b 1
if not exist "%YDNS_APK%" (
    echo !!! Не найден %YDNS_APK% 1>&2
    exit /b 1
)
if not exist "%YDNS_DIR%select-yandex-dns.ps1" (
    echo !!! Не найден %YDNS_DIR%select-yandex-dns.ps1 1>&2
    exit /b 1
)
where powershell.exe >nul 2>nul
if errorlevel 1 (
    echo !!! PowerShell не найден: нельзя проверить SHA-256 APK. 1>&2
    exit /b 1
)
set "YDNS_HASH_PATH=%YDNS_APK%"
set "YDNS_ACTUAL_SHA256="
for /f "usebackq delims=" %%H in (`powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%YDNS_DIR%select-yandex-dns.ps1" -HashPath "%YDNS_HASH_PATH%"`) do set "YDNS_ACTUAL_SHA256=%%H"
if not "%YDNS_ACTUAL_SHA256%"=="%YDNS_EXPECTED_SHA256%" (
    echo !!! SHA-256 DNS RRO APK не совпадает с зафиксированным. 1>&2
    exit /b 1
)
exit /b 0

:push_helper
call :check_common
if errorlevel 1 exit /b 1
"%YDNS_ADB%" push "%YDNS_DEVICE_HELPER%" "%YDNS_REMOTE_HELPER%" 1>&2
if errorlevel 1 exit /b 1
"%YDNS_ADB%" shell chmod 0700 "%YDNS_REMOTE_HELPER%" 1>&2
if errorlevel 1 exit /b 1
exit /b 0

:status
call :push_helper
if errorlevel 1 exit /b 1
"%YDNS_ADB%" shell sh "%YDNS_REMOTE_HELPER%" status
exit /b %errorlevel%

:install
call :push_helper
if errorlevel 1 exit /b 1
if not exist "%YDNS_APK%" (
    echo !!! Не найден %YDNS_APK% 1>&2
    exit /b 1
)
"%YDNS_ADB%" remount 1>&2
"%YDNS_ADB%" push "%YDNS_APK%" "%YDNS_REMOTE_APK%" 1>&2
if errorlevel 1 exit /b 1
"%YDNS_ADB%" shell chmod 0600 "%YDNS_REMOTE_APK%" 1>&2
if errorlevel 1 exit /b 1
"%YDNS_ADB%" shell sh "%YDNS_REMOTE_HELPER%" install
set "YDNS_RC=%errorlevel%"
"%YDNS_ADB%" shell rm -f "%YDNS_REMOTE_APK%" >nul 2>nul
exit /b %YDNS_RC%

:disable
call :push_helper
if errorlevel 1 exit /b 1
"%YDNS_ADB%" remount 1>&2
"%YDNS_ADB%" shell sh "%YDNS_REMOTE_HELPER%" disable
exit /b %errorlevel%

:restore
call :push_helper
if errorlevel 1 exit /b 1
"%YDNS_ADB%" remount 1>&2
"%YDNS_ADB%" shell sh "%YDNS_REMOTE_HELPER%" restore
exit /b %errorlevel%
