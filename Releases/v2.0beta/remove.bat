adb root
adb shell mount -o rw,remount /
adb shell rm -r /system/priv-app/Native
adb shell "ls -all /system/priv-app/Native"
adb shell pm uninstall ru.big.town.restoremode
adb reboot

