@echo off
setlocal EnableExtensions DisableDelayedExpansion
REM Windows host-side integration for the optional static Yandex DNS RRO.

set "YDNS_MODE=%~1"
set "YDNS_DIR=%~dp0"
set "YDNS_ADB=%YDNS_DIR%adb.exe"
set "YDNS_DEVICE_HELPER=%YDNS_DIR%dns-overlay-device.sh"
set "YDNS_APK=%YDNS_DIR%framework-res__config_ethernet_interfaces_yandexdns.apk"
set "YDNS_REMOTE_HELPER=/data/local/tmp/open_voyah_dns_overlay.sh"
set "YDNS_REMOTE_APK=/data/local/tmp/open_voyah_yandex_dns.apk"

if /i "%YDNS_MODE%"=="prepare" goto :prepare
if /i "%YDNS_MODE%"=="prepare-install" goto :prepare_install
if /i "%YDNS_MODE%"=="status" goto :status
if /i "%YDNS_MODE%"=="install" goto :install
if /i "%YDNS_MODE%"=="disable" goto :disable
if /i "%YDNS_MODE%"=="restore" goto :restore
echo Usage: dns-overlay.bat ^{prepare^|prepare-install^|status^|install^|disable^|restore^} 1>&2
exit /b 2

:check_common
if not exist "%YDNS_ADB%" (
    echo !!! Missing %YDNS_ADB% 1>&2
    exit /b 1
)
if not exist "%YDNS_DEVICE_HELPER%" (
    echo !!! Missing %YDNS_DEVICE_HELPER% 1>&2
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
    echo !!! Missing %YDNS_APK% 1>&2
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
    echo !!! Missing %YDNS_APK% 1>&2
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
