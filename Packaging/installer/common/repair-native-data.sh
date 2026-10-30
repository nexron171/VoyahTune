#!/system/bin/sh
set -eu

if [ ! -d /data/user_de/0/ru.big.town.anative ]; then
    am force-stop --user 0 ru.big.town.anative
    pm uninstall -k --user 0 ru.big.town.anative || true
    cmd package install-existing --user 0 ru.big.town.anative
    test -d /data/user_de/0/ru.big.town.anative
fi
