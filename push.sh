#!/bin/bash

#rm -f app/release/*.apk
#./gradlew assembleRelease
#./gradlew clean && ./gradlew assembleRelease --continue
adb shell pm uninstall ru.big.town.anative
adb shell pm uninstall ru.big.town.restoremode

adb install -r -g /home/big/AndroidStudioProjects/RestoreMode/app/debug/app-debug.apk
adb install -r -g /home/big/AndroidStudioProjects/Native/app/release/app-release.apk
