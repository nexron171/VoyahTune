@echo off
REM Удаление Open Voyah v3.2 — полный откат к состоянию ДО установки нашего приложения.
adb.exe root
adb.exe wait-for-device
adb.exe root
REM /system записываемым: снимаем verity (идемпотентно) + overlay-remount + сырой remount. Полный
REM ребут-цикл здесь не нужен (после install verity уже снята; если её вернул OTA - сначала install.bat).
adb.exe disable-verity >nul 2>nul
adb.exe remount >nul 2>nul
adb.exe shell "mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null"

REM --- Boot-хук: вернуть исходный init.logcat.sh (из backup если есть, иначе чистый оригинал) ---
if exist "backup\init.logcat.sh" (
    echo Restore init.logcat.sh из backup\
    adb.exe push backup\init.logcat.sh /system/etc/init.logcat.sh
) else (
    echo Restore чистого init.logcat.original.sh
    adb.exe push init.logcat.original.sh /system/etc/init.logcat.sh
)
adb.exe shell "chmod 644 /system/etc/init.logcat.sh"

REM --- Остановить наши живые Frida-хуки и load.bin (до ребута) ---
adb.exe shell "pkill -f /data/local/bin/load.bin"
adb.exe shell "ps -ef | grep frida-inject | grep -E 'vd_bypass|steeringwheelkeys|launcherdock' | grep -v grep | awk '{print $2}' | xargs kill -9"

REM --- Убрать наши Frida-файлы (или вернуть бэкап, если что-то было до нас) ---
if exist "backup\load.bin" (
    adb.exe push backup\load.bin /data/local/bin/load.bin
) else (
    adb.exe shell "rm -f /data/local/bin/load.bin"
)
adb.exe shell "rm -f /data/local/bin/vd_bypass.js"
adb.exe shell "rm -f /data/local/bin/steeringwheelkeys.js /data/local/bin/launcherdock.js /data/local/bin/keymng2.js"
if exist "backup\frida-inject" (
    adb.exe push backup\frida-inject /data/local/bin/frida-inject
) else (
    adb.exe shell "rm -f /data/local/bin/frida-inject"
)
REM Маркеры переинжекта (pid-файлы) — чтобы следующая установка гарантированно переинжектила хуки
adb.exe shell "rm -f /data/local/tmp/voyah_vd.pid /data/local/tmp/voyah_swk_ss.pid /data/local/tmp/voyah_swk_km.pid /data/local/tmp/voyah_km.pid /data/local/tmp/voyah_lnch.pid"
REM Опционально: почистить конфиг дока в Settings.Global (иначе останется до следующей установки)
adb.exe shell settings delete global voyahtune_dock1 2>nul
adb.exe shell settings delete global voyahtune_dock2 2>nul
adb.exe shell settings delete global voyahtune_dock1Dpi 2>nul
adb.exe shell settings delete global voyahtune_dock2Dpi 2>nul

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
echo "Press any key..."
pause
