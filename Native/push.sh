#!/bin/bash

#rm -f app/release/*.apk
#./gradlew assembleRelease
#./gradlew clean && ./gradlew assembleRelease --continue
adb shell pm uninstall ru.big.town.anative

adb install -r -g app/release/app-release.apk
#adb install -r -g app/debug/app-debug.apk
