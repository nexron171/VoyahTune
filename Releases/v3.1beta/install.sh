adb root
adb root
adb pull /sdcard/Download/cunba/patch/load.bin load.bin.bak
adb push load.bin /sdcard/Download/cunba/patch/load.bin
adb push frida-inject-16.2.1-android-arm64 /data/local/bin/frida-server
adb shell "chmod 755 /data/local/bin/frida*"
adb shell mount -o rw,remount /
adb shell mkdir -p /system/priv-app/Native
adb shell chmod 755 /system/priv-app/Native
adb push native.apk /system/priv-app/Native/Native.apk
adb shell "ls -all /system/priv-app/Native"
adb install -r -g restore_mode.apk
adb reboot

