adb root
adb shell mount -o rw,remount /
adb shell mkdir -p /system/priv-app/Native
adb shell chmod 755 /system/priv-app/Native
adb push /home/big/AndroidStudioProjects/Native/app/release/app-release.apk /system/priv-app/Native/Native.apk
adb shell "ls -all /system/priv-app/Native"
