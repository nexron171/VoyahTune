#!/system/bin/sh
set -eu
pm uninstall ru.big.town.updater >/dev/null 2>&1 || true
pm uninstall --user 0 ru.big.town.updater >/dev/null 2>&1 || true
active=$(pm path ru.big.town.updater 2>/dev/null || true)
case "$active" in
    *package:/data/*) echo 'Updater update could not be uninstalled' >&2; exit 1 ;;
esac
# PackageManager drops the old system signing identity on the following reboot.
rm -f /system/priv-app/VoyahTuneUpdater/VoyahTuneUpdater.apk
sync
