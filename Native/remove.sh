adb root
adb shell mount -o rw,remount /
adb shell rm -rf /system/priv-app/Native
#adb shell reboot