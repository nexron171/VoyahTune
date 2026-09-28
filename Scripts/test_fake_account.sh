#!/bin/sh

adb root
adb shell "echo 'Owner%+79991234567%1%100001%100001' > /private/configs/token/accountInfo"
adb shell "chmod 444 /private/configs/token/accountInfo"
adb shell "chown root:root /private/configs/token/accountInfo"