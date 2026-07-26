#!/bin/sh
# Удаление Open Voyah v3.2 — полный откат к состоянию ДО установки нашего приложения.
adb root
adb wait-for-device
adb root
# /system записываемым: снимаем verity (идемпотентно) + overlay-remount + сырой remount. Полный
# ребут-цикл здесь не нужен (после install verity уже снята; если её вернул OTA — сначала прогнать
# install.sh, он снимет verity и перезагрузит).
adb disable-verity >/dev/null 2>&1
adb remount >/dev/null 2>&1
adb shell 'mount -o rw,remount /system 2>/dev/null; mount -o rw,remount / 2>/dev/null'

# --- Boot-хук: вернуть исходный init.logcat.sh (из backup если есть, иначе чистый оригинал) ---
if [ -f backup/init.logcat.sh ]; then
    echo "Restore init.logcat.sh из backup/"
    adb push backup/init.logcat.sh /system/etc/init.logcat.sh
else
    echo "Restore чистого init.logcat.original.sh"
    adb push init.logcat.original.sh /system/etc/init.logcat.sh
fi
adb shell "chmod 644 /system/etc/init.logcat.sh"

# --- Остановить наши живые Frida-хуки и load.bin (до ребута) ---
adb shell "pkill -f /data/local/bin/load.bin" 2>/dev/null
adb shell "ps -ef | grep frida-inject | grep -E 'vd_bypass|steeringwheelkeys|launcherdock' | grep -v grep | awk '{print \$2}' | xargs kill -9" 2>/dev/null

# --- Убрать наши Frida-файлы (или вернуть бэкап, если что-то было до нас) ---
if [ -f backup/load.bin ]; then adb push backup/load.bin /data/local/bin/load.bin; else adb shell "rm -f /data/local/bin/load.bin"; fi
adb shell "rm -f /data/local/bin/vd_bypass.js"
adb shell "rm -f /data/local/bin/steeringwheelkeys.js /data/local/bin/launcherdock.js /data/local/bin/keymng2.js"   # keymng2 — легаси до объединения хуков руля
if [ -f backup/frida-inject ]; then adb push backup/frida-inject /data/local/bin/frida-inject; else adb shell "rm -f /data/local/bin/frida-inject"; fi
# Маркеры переинжекта (pid-файлы) — чтобы следующая установка гарантированно переинжектила хуки
adb shell "rm -f /data/local/tmp/voyah_vd.pid /data/local/tmp/voyah_swk_ss.pid /data/local/tmp/voyah_swk_km.pid /data/local/tmp/voyah_km.pid /data/local/tmp/voyah_lnch.pid"
# Опционально: почистить конфиг дока в Settings.Global (иначе останется до следующей установки)
adb shell settings delete global voyahtune_dock1 2>/dev/null
adb shell settings delete global voyahtune_dock2 2>/dev/null
adb shell settings delete global voyahtune_dock1Dpi 2>/dev/null
adb shell settings delete global voyahtune_dock2Dpi 2>/dev/null

# --- Whitelist + Native из /system/priv-app (+ снять /data-оверлей обновления) ---
adb shell "rm -f /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml"
adb shell "rm -rf /system/priv-app/Native"
adb shell "ls -all /system/priv-app/Native"
adb shell pm uninstall ru.big.town.anative
adb shell pm uninstall ru.big.town.restoremode
adb shell am force-stop ru.big.town.anative
# Полностью вычистить data-каталоги Native: pm uninstall системного priv-app не всегда их удаляет,
# а протухшие данные ломают СЛЕДУЮЩУЮ установку (краш zygote при монтировании data_de/null/…).
adb shell "rm -rf /data/user/0/ru.big.town.anative /data/user_de/0/ru.big.town.anative /data/data/ru.big.town.anative"

# --- Откат наших global settings (freeform для VirtualDisplay-сплита) ---
adb shell settings delete global enable_freeform_support
adb shell settings delete global force_resizable_activities
# Примечание: persist.app.feature.leavecar (power hold) НЕ откатываем — это штатная функция авто.

adb reboot
