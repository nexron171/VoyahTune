#!/bin/bash

#./gradlew clean && ./gradlew assembleRelease
#./gradlew assembleRelease
adb shell pm uninstall ru.big.town.anative

adb install -r -g app/release/app-release.apk
#adb install -r -g app/debug/app-debug.apk
