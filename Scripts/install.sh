#!/bin/bash

adb root
adb shell settings put global voyahtune_install_mode full

adb shell "mkdir -p /data/local/tmp ; mkdir -p /data/local/bin ; chmod 777 /data/local/bin ; chmod 777 /data/local/tmp"

adb push loaderFrida /data/local/bin
adb push apollo_tech.js /data/local/bin
adb push clusternavi.js /data/local/bin
adb push keyboard_lock_en.js /data/local/bin
adb push keyboard_ru.js /data/local/bin
adb push launcherdock.js /data/local/bin
adb push multidisplay.js /data/local/bin
adb push steeringwheelkeys.js /data/local/bin
adb push vd_bypass.js /data/local/bin
adb push injects.json /data/local/bin
adb push voyahtune_keyboard_en_config.json /data/local/bin
adb push voyahtune_keyboard_ru_config.json /data/local/bin
adb push voyahtune_skb_qwerty_ru.json /data/local/bin

adb shell "mount -o rw,remount /"

adb push init.voyah_tune.rc /system/etc/init
adb push privapp-permissions-ru.big.town.anative.xml /system/etc/permissions/
adb push  init.logcat.sh /system/etc/

adb shell "mkdir -p /system/priv-app/Native"
adb push Native.apk /system/priv-app/Native
adb install -r -g restore-mode.apk
adb install -r -g RunYN.apk

echo "Если что то пошло не так, то запускаем в терминале"
echo "  Mac, Linux /install.sh > install.log 2>&1"
echo "  Windows10 install-win10.bat > install.log 2>&1"
echo "  Windows11 install-win11.bat > install.log 2>&1"
echo "И отправляем в чат разработчикам https://t.me/VoyahTuneChat/"
echo "Со скриншотом сведений о системе своего авто."
sleep 10 

