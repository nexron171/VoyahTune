#!/bin/bash

adb root

adb shell "rm -f /data/local/bin/loaderFrida"
adb shell "rm -f /data/local/bin/apollo_tech.js"
adb shell "rm -f /data/local/bin/clusternavi.js"
adb shell "rm -f /data/local/bin/keyboard_lock_en.js"
adb shell "rm -f /data/local/bin/keyboard_ru.js"
adb shell "rm -f /data/local/bin/launcherdock.js"
adb shell "rm -f /data/local/bin/multidisplay.js"
adb shell "rm -f /data/local/bin/steeringwheelkeys.js"
adb shell "rm -f /data/local/bin/vd_bypass.js"
adb shell "rm -f /data/local/bin/injects.js"
adb shell "rm -f /data/local/bin/voyahtune_keyboard_en_config.js"
adb shell "rm -f /data/local/bin/voyahtune_keyboard_ru_config.js"
adb shell "rm -f /data/local/bin/voyahtune_skb_qwerty_ru.js"

adb shell "pm uninstall big.town.runyn"
adb shell "pm uninstall ru.big.town.restoremode"


adb shell "mount -o rw,remount /"


adb shell  "rm -f /system/etc/init/init.voyah_tune.rc"
adb shell  "rm -f /system/etc/permissions/privapp-permissions-ru.big.town.anative.xml"

adb shell "rm -rf /system/priv-app/Native"
adb shell "rm -rf /data/user/0/ru.big.town.anative"


adb push  init.logcat.original.sh /system/etc/init.logcat.sh

echo "Если что то пошло не так, то запускаем в терминале"
echo "  Mac, Linux ./uninstall.sh > install.log 2>&1"
echo "  Windows10 uninstall-win10.bat > install.log 2>&1"
echo "  Windows11 uninstall-win11.bat > install.log 2>&1"
echo "И отправляем в чат разработчикам https://t.me/VoyahTuneChat/"
echo "Со скриншотом сведений о системе своего авто."
sleep 10 

