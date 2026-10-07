adb root
adb shell "mount -o rw,remount /"
adb shell "cp -R /system/priv-app/dueros /sdcard"
adb shell "rm -rf /system/priv-app/dueros"