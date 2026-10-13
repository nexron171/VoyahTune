set -eu

found() {
    printf 'CUNBA_FOUND\t%s\n' "$1"
    exit 0
}

# A single remnant is enough; a detached agent may outlive its injector.
for location in \
    /sdcard/Download/cunba \
    /storage/emulated/0/Download/cunba \
    /data/media/0/Download/cunba \
    /data/local/bin/frida-inject2 \
    /system/bin/frida-inject2 \
    /system/xbin/frida-inject2
do
    if [ -e "$location" ] || [ -L "$location" ]; then
        found "file: $location"
    fi
done

[ "$(id -u)" = 0 ] || { echo 'Root access required' >&2; exit 1; }
packages=$(pm list packages -u --user 0) || exit 1
packages=$(printf '%s\n' "$packages" | tr '[:upper:]' '[:lower:]')
printf '%s\n' "$packages" | grep -qx 'package:android' || exit 1
while IFS= read -r package; do
    case "$package" in
        *cunba*) found "package: $package" ;;
    esac
done <<EOF
$packages
EOF

processes=$(ps -A -o PID,ARGS) || exit 1
printf '%s\n' "$processes" | grep -Eq '^[[:space:]]*[0-9]+[[:space:]]+' || exit 1
processes=$(printf '%s\n' "$processes" | tr '[:upper:]' '[:lower:]')
while IFS= read -r process; do
    case "$process" in
        *cunba*|*frida-inject2*|*launcher2.bin*|*systemserver.bin*)
            found "process: $process" ;;
    esac
done <<EOF
$processes
EOF

for startup in \
    /system/etc/init*.sh \
    /system/etc/init/*.rc \
    /system/etc/init/*.sh \
    /vendor/etc/init/*.rc \
    /vendor/etc/init/*.sh \
    /odm/etc/init/*.rc \
    /product/etc/init/*.rc \
    /system_ext/etc/init/*.rc \
    /data/adb/service.d/* \
    /data/adb/post-fs-data.d/* \
    /data/local/userinit.sh \
    /data/local/userinit.d/*
do
    [ -f "$startup" ] || continue
    case "$(printf '%s' "$startup" | tr '[:upper:]' '[:lower:]')" in
        *cunba*) found "startup: $startup" ;;
    esac
    if grep -Eiq 'cunba|frida-inject2|launcher2[.]bin|systemserver[.]bin' "$startup"; then
        found "startup: $startup"
    else
        status=$?
        [ "$status" = 1 ] || exit "$status"
    fi
done
printf 'CUNBA_CLEAR\n'
