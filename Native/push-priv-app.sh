#!/bin/bash
# Требует запуска эмулятора с флагом -writable-system:
#   ~/Library/Android/sdk/emulator/emulator -avd <AVD_NAME> -writable-system

adb root
adb remount
adb shell mkdir -p /system/priv-app/Native
adb shell chmod 755 /system/priv-app/Native
adb push app/release/app-release.apk /system/priv-app/Native/Native.apk
adb shell chmod 644 /system/priv-app/Native/Native.apk
adb shell "ls -all /system/priv-app/Native"
