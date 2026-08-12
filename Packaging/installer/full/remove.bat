@echo off
chcp 65001 >nul
cd /d "%~dp0" || exit /b 1
REM Удаление Open Voyah v@VERSION@ — полный откат к состоянию ДО установки нашего приложения.
set "YDNS_HELPER=%~dp0dns-overlay.bat"
if not exist "%YDNS_HELPER%" (
    echo !!! Не найден dns-overlay.bat - удаление прервано до изменения устройства.
    exit /b 1
)
call "%YDNS_HELPER%" prepare
if errorlevel 1 (
    echo !!! DNS-overlay helper не готов - удаление прервано до изменения устройства.
    exit /b 1
)

adb.exe root
adb.exe wait-for-device
adb.exe root
REM /system записываемым: снимаем verity (идемпотентно) + overlay-remount + сырой remount. Полный
REM ребут-цикл здесь не нужен (после install verity уже снята; если её вернул OTA - сначала install.bat).
adb.exe disable-verity >nul 2>nul
adb.exe remount >nul 2>nul
adb.exe shell "mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null"

REM До любых удалений переводим master в 0 и проверяем фактическое значение.
adb.exe shell settings put global open_voyah_apollo_master 0
if errorlevel 1 (
    echo !!! Не удалось выключить Apollo master - удаление прервано до выгрузки hook.
    exit /b 1
)
set APOLLO_MASTER_STATE=
for /f "delims=" %%i in ('adb.exe shell settings get global open_voyah_apollo_master 2^>nul') do set APOLLO_MASTER_STATE=%%i
if not "%APOLLO_MASTER_STATE%"=="0" (
    echo !!! Apollo master не подтвердил состояние 0 - удаление прервано до выгрузки hook.
    exit /b 1
)
REM Живой hook получает короткое окно на один best-effort штатный subscription resync.
timeout /t 3 /nobreak >nul

echo === Откат DNS-overlay ===
call "%YDNS_HELPER%" restore
if errorlevel 1 (
    echo !!! Не удалось восстановить/отключить DNS-overlay - остальные компоненты не удалялись.
    exit /b 1
)

REM --- Boot-хук: убрать свои RC-сервисы (init.logcat.sh никогда не трогался - восстанавливать нечего) ---
echo Remove voyahtune.*.rc/.sh из /system/etc/init
adb.exe shell "rm -f /system/etc/init/voyahtune.setenforce.rc /system/etc/init/voyahtune.load.rc /system/etc/init/voyahtune.load.sh"

REM --- Остановить наши живые Frida-хуки и load.bin (до ребута) ---
adb.exe shell "pkill -f /data/local/bin/load.bin"
adb.exe shell "rm -f /data/local/tmp/voyah_load.v2.lock"
adb.exe shell "rm -rf /data/local/tmp/voyah_load.lock"
adb.exe shell "ps -ef | grep frida-inject | grep -E 'vd_bypass|steeringwheelkeys|launcherdock|multidisplay|apollo_tech' | grep -v grep | awk '{print $2}' | xargs kill -9"
REM Eternalized agent живёт в target без frida-inject; force-stop выгружает его до финального reboot.
adb.exe shell "am force-stop com.qinggan.app.vehiclesetting"

REM --- Убрать наши Frida-файлы (или вернуть бэкап, если что-то было до нас) ---
if exist "backup\load.bin" (
    adb.exe push backup\load.bin /data/local/bin/load.bin
) else (
    adb.exe shell "rm -f /data/local/bin/load.bin"
)
adb.exe shell "rm -f /data/local/bin/vd_bypass.js"
adb.exe shell "rm -f /data/local/bin/steeringwheelkeys.js /data/local/bin/launcherdock.js /data/local/bin/multidisplay.js /data/local/bin/keymng2.js"
REM Apollo-файл имеет симметричный backup: восстанавливаем прежний либо подтверждённое отсутствие.
if exist "backup\apollo_tech.js" (
    adb.exe push backup\apollo_tech.js /data/local/bin/apollo_tech.js.new
    if errorlevel 1 (
        adb.exe shell "rm -f /data/local/bin/apollo_tech.js.new" 1>nul 2>nul
        echo !!! Не удалось восстановить backup\apollo_tech.js - удаление прервано.
        exit /b 1
    )
    adb.exe shell "chmod 644 /data/local/bin/apollo_tech.js.new && mv -f /data/local/bin/apollo_tech.js.new /data/local/bin/apollo_tech.js"
    if errorlevel 1 (
        adb.exe shell "rm -f /data/local/bin/apollo_tech.js.new" 1>nul 2>nul
        echo !!! Не удалось завершить восстановление apollo_tech.js - удаление прервано.
        exit /b 1
    )
) else (
    if exist "backup\apollo_tech.js.absent" (
        adb.exe shell "rm -f /data/local/bin/apollo_tech.js /data/local/bin/apollo_tech.js.new"
        if errorlevel 1 (
            echo !!! Не удалось вернуть подтверждённо отсутствовавший apollo_tech.js - удаление прервано.
            exit /b 1
        )
    ) else (
        echo Backup metadata для apollo_tech.js нет - неизвестный существующий файл оставлен без изменений
        adb.exe shell "rm -f /data/local/bin/apollo_tech.js.new"
        if errorlevel 1 (
            echo !!! Не удалось удалить временный apollo_tech.js.new - удаление прервано.
            exit /b 1
        )
    )
)
if exist "backup\frida-inject" (
    adb.exe push backup\frida-inject /data/local/bin/frida-inject
) else (
    adb.exe shell "rm -f /data/local/bin/frida-inject"
)
REM Маркеры переинжекта (pid-файлы) — чтобы следующая установка гарантированно переинжектила хуки
adb.exe shell "rm -f /data/local/tmp/voyah_vd.pid /data/local/tmp/voyah_swk_ss.pid /data/local/tmp/voyah_swk_km.pid /data/local/tmp/voyah_swk_km.busy /data/local/tmp/voyah_swk.*.try /data/local/tmp/voyah_km.pid /data/local/tmp/voyah_lnch.pid /data/local/tmp/voyah_md.pid /data/local/tmp/voyah_apollo.pid /data/local/tmp/voyah_apollo.down /data/local/tmp/voyah_apollo.txt /data/local/tmp/voyah_apollo.txt.1 /data/local/tmp/voyah_apollo.txt.try"
REM Почистить конфиг дока и кнопок руля в Settings.Global, чтобы чистая переустановка
REM не подхватила старые назначения до первой синхронизации из RestoreMode.
adb.exe shell settings delete global voyahtune_dock1 2>nul
adb.exe shell settings delete global voyahtune_dock2 2>nul
adb.exe shell settings delete global voyahtune_dock1Dpi 2>nul
adb.exe shell settings delete global voyahtune_dock2Dpi 2>nul
adb.exe shell settings delete global voyahtune_steerStarShort 2>nul
adb.exe shell settings delete global voyahtune_steerStarLong 2>nul
adb.exe shell settings delete global voyahtune_steerDvrShort 2>nul
adb.exe shell settings delete global voyahtune_steerDvrLong 2>nul
adb.exe shell settings delete global voyahtune_steerVoiceShort 2>nul
adb.exe shell settings delete global voyahtune_steerVoiceLong 2>nul
adb.exe shell settings delete global voyahtune_steerPhoneShort 2>nul
adb.exe shell settings delete global voyahtune_steerPhoneLong 2>nul
adb.exe shell settings delete global open_voyah_apollo_master 2>nul
adb.exe shell settings delete global open_voyah_apollo_asc 2>nul
adb.exe shell settings delete global open_voyah_apollo_sdb 2>nul
adb.exe shell settings delete global open_voyah_apollo_profile_supported 2>nul
adb.exe shell settings delete global open_voyah_apollo_profile_heartbeat 2>nul

REM --- Whitelist + Native из /system/priv-app (+ снять /data-оверлей обновления) ---
adb.exe shell "rm -f /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml"
adb.exe shell "rm -rf /system/priv-app/Native"
adb.exe shell "ls -all /system/priv-app/Native"
adb.exe shell pm uninstall ru.big.town.anative
adb.exe shell pm uninstall ru.big.town.restoremode
adb.exe shell am force-stop ru.big.town.anative
REM Полностью вычистить data-каталоги Native (лечение краха zygote "data_de/null" при след. установке)
adb.exe shell "rm -rf /data/user/0/ru.big.town.anative /data/user_de/0/ru.big.town.anative /data/data/ru.big.town.anative"

REM --- Откат наших global settings (freeform для VirtualDisplay-сплита) ---
adb.exe shell settings delete global enable_freeform_support
adb.exe shell settings delete global force_resizable_activities
REM Примечание: persist.app.feature.leavecar (power hold) НЕ откатываем — штатная функция авто.

adb.exe reboot
if errorlevel 1 (
    echo !!! Откат подготовлен, но ADB не смог перезагрузить ГУ. Выполните reboot вручную.
    pause
    exit /b 1
)
echo "Press any key..."
pause
exit /b 0
