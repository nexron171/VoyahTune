set -eu

native_system_apk=${VOYAHTUNE_SYSTEM_NATIVE_APK:-/system/priv-app/Native/Native.apk}
native_paths=$(pm path --user 0 ru.big.town.anative)
[ "$native_paths" = "package:$native_system_apk" ] || {
    printf 'Native must run from %s; active: %s\n' "$native_system_apk" "$native_paths" >&2
    exit 1
}
dumpsys package ru.big.town.anative | awk '
    /^[ \t]*flags=\[/ && !seen_flags {
        seen_flags = 1
        is_system = $0 ~ /(^|[ \t])SYSTEM([ \t]|\])/
        updated = $0 ~ /UPDATED_SYSTEM_APP/
    }
    /^[ \t]*privateFlags=\[/ {
        privileged = $0 ~ /(^|[ \t])PRIVILEGED([ \t]|\])/
        exit
    }
    END { exit !(seen_flags && is_system && !updated && privileged) }
' || {
    echo 'Native is not a bundled privileged system application' >&2
    exit 1
}
