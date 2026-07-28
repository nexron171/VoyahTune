#!/bin/sh
# Удаление Open Voyah v@VERSION@-LIGHT — полный откат к состоянию ДО установки.
# LIGHT ничего не инжектит и не трогает init.logcat.sh/Frida — чистим только priv-app + whitelist + оба APK.
adb root
adb wait-for-device
adb shell mount -o rw,remount /

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

# Примечание: persist.app.feature.leavecar (power hold) НЕ откатываем — это штатная функция авто.

adb reboot
