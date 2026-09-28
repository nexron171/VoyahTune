#!/bin/sh

adb root
adb shell "mount -o rw,remount / ; > fake_login"
adb shell "mkdir /tmp ; > /tmp/fake_login"
adb shell "echo 'Owner%+79991234567%1%100001%100001' > /private/configs/token/accountInfo"
adb shell "chmod 444 /private/configs/token/accountInfo"
adb shell "chown root:root /private/configs/token/accountInfo"