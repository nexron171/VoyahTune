android_identity() {
    [ "$(id -u)" = 0 ] || return 1
    [ "$(getprop sys.boot_completed)" = 1 ] || return 1
    service check activity | grep -q ': found' || return 1
    service check package | grep -q ': found' || return 1
    server=$(pidof system_server) || return 1
    case "$server" in ''|*[!0-9]*) return 1 ;; esac
    boot=$(cat /proc/sys/kernel/random/boot_id) || return 1
    started=$(sed 's/.*) //' /proc/"$server"/stat | awk '{print $20}')
    [ -n "$boot" ] && [ -n "$started" ] || return 1
    printf '%s:%s:%s' "$boot" "$server" "$started"
}

probe_native_data() {
    before=$(android_identity) || { echo UNAVAILABLE; return; }
    packages=$(pm list packages --user 0) || { echo UNAVAILABLE; return; }
    printf '%s\n' "$packages" | grep -qx 'package:android' || { echo UNAVAILABLE; return; }
    details=$(dumpsys package ru.big.town.anative) || { echo UNAVAILABLE; return; }
    native_uid=$(printf '%s\n' "$details" | sed -n 's/^[[:space:]]*userId=\([0-9]*\)$/\1/p' | head -1)
    case "$native_uid" in ''|*[!0-9]*) echo UNAVAILABLE; return ;; esac
    state=UNREGISTERED
    if printf '%s\n' "$packages" | grep -qx 'package:ru.big.town.anative'; then
        paths=$(pm path --user 0 ru.big.town.anative) || { echo UNAVAILABLE; return; }
        case "$paths" in package:/*) ;; *) echo UNAVAILABLE; return ;; esac
        state=READY
        for directory in /data/user/0/ru.big.town.anative /data/user_de/0/ru.big.town.anative; do
            if [ ! -d "$directory" ] || [ -L "$directory" ]; then
                state=BROKEN
            elif [ "$(stat -c %u:%g "$directory")" != "$native_uid:$native_uid" ]; then
                state=BROKEN
            elif ! ls -Zd "$directory" | grep -q 'u:object_r:privapp_data_file:'; then
                state=BROKEN
            fi
        done
    fi
    after=$(android_identity) || { echo UNAVAILABLE; return; }
    [ "$before" = "$after" ] || { echo UNAVAILABLE; return; }
    printf '%s|%s|%s\n' "$state" "$before" "$native_uid"
}
