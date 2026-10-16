set -eu

found() {
    printf 'VOYAHTWEAKS_FOUND\t%s\n' "$1"
    exit 0
}

[ "$(id -u)" = 0 ] || { echo 'Root access required' >&2; exit 1; }
recovery=/data/local/voyahtune-voyahtweaks-recovery
[ ! -L "$recovery" ] || found 'recovery: unsafe journal'
if [ -d "$recovery" ] && [ "${VOYAHTWEAKS_RECOVERY_VERIFY:-0}" != 1 ]; then
    [ "$(cat "$recovery/state" 2>/dev/null)" = verified ] || found 'recovery: pending or failed'
fi

# These legacy paths are separate from VoyahTune OD's /data/local/bin resources.
for location in /data/local/tmp/load.bin /data/local/tmp/launcher.bin /data/local/tmp/ss11.bin /data/local/tmp/ss9.bin; do
    if [ -e "$location" ] || [ -L "$location" ]; then found "file: $location"; fi
done
packages=$(pm list packages --user 0) || exit 1
printf '%s\n' "$packages" | grep -qx 'package:android' || exit 1
while IFS= read -r package; do
    case "$package" in
        package:ru.kachalin.voyahtweaks)
            if [ -f "$recovery/package-state" ]; then
                disabled=$(pm list packages -d --user 0) || exit 1
                if printf '%s\n' "$disabled" | grep -qx "$package"; then continue; fi
            fi
            found "package: $package" ;;
        *voyahtweaks*) found "package: $package" ;;
    esac
done <<PACKAGES
$packages
PACKAGES

processes=$(ps -A -o PID,ARGS) || exit 1
printf '%s\n' "$processes" | grep -Eq '^[[:space:]]*[0-9]+[[:space:]]+' || exit 1
while IFS= read -r process; do
    case "$process" in
        *ru.kachalin.voyahtweaks*|*'/data/local/tmp/load.bin'*|*'/data/local/tmp/launcher.bin'*|*'/data/local/tmp/ss11.bin'*|*'/data/local/tmp/ss9.bin'*)
            found "process: $process" ;;
    esac
done <<PROCESSES
$processes
PROCESSES

for startup in /system/etc/init*.sh /system/etc/init/*.rc /system/etc/init/*.sh \
    /vendor/etc/init/*.rc /vendor/etc/init/*.sh /odm/etc/init/*.rc \
    /product/etc/init/*.rc /system_ext/etc/init/*.rc /data/adb/service.d/* \
    /data/adb/post-fs-data.d/* /data/local/userinit.sh /data/local/userinit.d/*; do
    [ -f "$startup" ] || continue
    if grep -Eiq 'voyahtweaks|/data/local/tmp/(load|launcher|ss11|ss9)[.]bin' "$startup"; then
        found "startup: $startup"
    else
        status=$?
        [ "$status" = 1 ] || exit "$status"
    fi
done
printf 'VOYAHTWEAKS_CLEAR\n'
