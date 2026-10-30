#!/system/bin/sh
set -eu
pm uninstall ru.big.town.anative >/dev/null 2>&1 || true
pm uninstall --user 0 ru.big.town.anative >/dev/null 2>&1 || true
active=$(pm path ru.big.town.anative 2>/dev/null || true)
case "$active" in
    *package:/data/*) echo 'Native update could not be uninstalled' >&2; exit 1 ;;
esac
rm -f /system/priv-app/Native/Native.apk
sync
