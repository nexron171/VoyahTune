adb.exe root
adb.exe shell mount -o rw,remount /
adb.exe shell mkdir -p /system/priv-app/Native
adb.exe shell chmod 755 /system/priv-app/Native
adb.exe push native.apk /system/priv-app/Native/Native.apk
adb.exe shell "ls -all /system/priv-app/Native"
adb.exe install -r -g restore_mode.apk
adb.exe reboot
echo "Press any key..."
pause

