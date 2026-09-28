#!/bin/sh

adb shell settings put global voyahtune_install_mode full
adb shell am force-stop ru.big.town.restoremode
adb shell am force-stop ru.big.town.anative