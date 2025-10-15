adb root
adb shell mount -o rw,remount /
adb shell mkdir -p /system/priv-app/Native
adb shell chmod 755 /system/priv-app/Native
adb push native.apk /system/priv-app/Native/Native.apk
adb shell "ls -all /system/priv-app/Native"
adb install -r -g restore_mode.apk
adb reboot

