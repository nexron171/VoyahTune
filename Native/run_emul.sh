#/home/big/Android/Sdk/emulator/emulator -avd Automotive_1408p_landscape -writable-system 
#adb root
#adb shell avbctl disable-verification
#adb disable-verity
#adb remount
#adb shell "su 0 mount -o rw,remount /system"
