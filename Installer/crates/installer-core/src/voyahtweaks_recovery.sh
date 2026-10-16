set -eu
journal=/data/local/voyahtune-voyahtweaks-recovery
lock=/data/local/voyahtune-install.lock
startup=/system/etc/init.logcat.sh
package=ru.kachalin.voyahtweaks
files='load.bin frida-inject launcher.bin launcher.js keymanager.bin keymanager.js rus.bin rus.js ss11.bin ss11.js ss9.bin ss9.js instrcard.bin instrcard.js virtualdisplay.bin virtualdisplay.js complex.bin complex.js login.bin login.js'

fail() { echo "$*" >&2; exit 1; }
exists() { [ -e "$1" ] || [ -L "$1" ]; }
digest() { sha256sum "$1" | cut -d ' ' -f 1; }
state() { printf '%s\n' "$1" > "$journal/state.new"; mv "$journal/state.new" "$journal/state"; sync; }
require_lock() {
    [ ! -L "$lock" ] && [ -d "$lock" ] || fail 'Installation lock missing'
    [ "$(cat "$lock/owner")" = desktop:voyahtweaks-recovery ] || fail 'Another operation owns the lock'
}
release_lock() {
    require_lock
    rm "$lock/owner" "$lock/boot"
    rmdir "$lock"
    sync
}
write_startup() {
    # Preserve the inode and SELinux label, and restore the original mount mode.
    target=/system
    options=$(awk '$2 == "/system" {print $4}' /proc/mounts)
    if [ -z "$options" ]; then
        target=/
        options=$(awk '$2 == "/" {print $4}' /proc/mounts)
    fi
    [ -n "$options" ] || fail 'System mount unavailable'
    case ",$options," in
        *,ro,*)
            mount -o remount,rw "$target"
            trap 'mount -o remount,ro "$target"' EXIT
            cat "$1" > "$startup"
            sync
            mount -o remount,ro "$target"
            trap - EXIT
            ;;
        *,rw,*) cat "$1" > "$startup"; sync ;;
        *) fail 'Unknown system mount mode' ;;
    esac
}
stop_loader() {
    for process in /proc/[0-9]*; do
        [ -r "$process/cmdline" ] || continue
        command=$(tr '\000' ' ' < "$process/cmdline" 2>/dev/null) || continue
        case "$command" in
            'sh /data/local/tmp/load.bin ' | '/system/bin/sh /data/local/tmp/load.bin ')
                kill -KILL "${process##*/}" 2>/dev/null || true ;;
        esac
    done
}
[ "$(id -u)" = 0 ] || fail 'Root access required'
[ ! -L "$journal" ] || fail 'Unsafe journal link'
case "$action" in
    prepare)
        ! exists "$journal" || fail 'Previous recovery exists'
        [ ! -L /data/local ] && [ ! -L /data/local/tmp ] && [ ! -L "$startup" ] || fail 'Unsupported parent or startup link'
        [ -f /data/local/tmp/load.bin ] && [ ! -L /data/local/tmp/load.bin ] || fail 'Unknown or missing loader; automatic isolation is not supported'
        grep -q 'VoyahTweaksSH' /data/local/tmp/load.bin || fail 'Loader ownership is not confirmed'
        grep -q 'ru.kachalin.voyahtweaks' /data/local/tmp/load.bin || fail 'Loader ownership is not confirmed'
        for name in $files; do
            path=/data/local/tmp/$name
            [ ! -L "$path" ] || fail "Unsupported link: $path"
            if exists "$path"; then [ -f "$path" ] || fail "Not a regular file: $path"; fi
        done
        packages=$(pm list packages --user 0)
        printf '%s\n' "$packages" | grep -qx 'package:android' || fail 'Package inventory incomplete'
        package_state=absent
        if printf '%s\n' "$packages" | grep -qx "package:$package"; then
            package_dump=$(dumpsys package "$package")
            package_state=$(printf '%s\n' "$package_dump" | sed -n 's/.*User 0:.* enabled=\([0-4]\).*/\1/p')
            case "$package_state" in 0|1|2|3|4) ;; *) fail 'Unknown package state' ;; esac
        fi
        mkdir "$lock" 2>/dev/null || fail 'Another installation or OTA owns the device'
        printf '%s' desktop:voyahtweaks-recovery > "$lock/owner"
        cat /proc/sys/kernel/random/boot_id > "$lock/boot"
        chmod 700 "$lock"
        trap '[ -d "$journal" ] || release_lock' EXIT
        mkdir -m 700 "$journal"
        state preparing
        printf '%s\n' "$package_state" > "$journal/package-state"
        cat /proc/sys/kernel/random/boot_id > "$journal/boot-before"
        mkdir "$journal/files"
        : > "$journal/files-present"
        for name in $files; do
            if [ -f "/data/local/tmp/$name" ]; then
                printf '%s\n' "$name" >> "$journal/files-present"
                digest "/data/local/tmp/$name" > "$journal/$name.sha256"
            fi
        done
        cp -p "$startup" "$journal/startup"
        digest "$startup" > "$journal/startup-before"
        awk "$startup_filter" "$startup" > "$journal/startup-disabled" || fail 'Unknown startup commands; automatic changes are not supported'
        /system/bin/sh -n "$journal/startup-disabled"
        digest "$journal/startup-disabled" > "$journal/startup-after"
        state prepared
        trap - EXIT
        ;;
    apply)
        require_lock
        [ "$(cat "$journal/state")" = prepared ] || fail 'Recovery not prepared'
        [ "$(digest "$startup")" = "$(cat "$journal/startup-before")" ] || fail 'Startup changed since inspection'
        state applying
        if [ "$(cat "$journal/package-state")" != absent ]; then
            pm disable-user --user 0 "$package"
            pm list packages -d --user 0 | grep -qx "package:$package" || fail 'Package not disabled'
        fi
        : > "$journal/startup-write-started"
        sync
        write_startup "$journal/startup-disabled"
        [ "$(digest "$startup")" = "$(cat "$journal/startup-after")" ] || fail 'Startup verification failed'
        # Moving the loader first prevents the already running watchdog from reinjecting.
        while IFS= read -r name; do
            [ "$(digest "/data/local/tmp/$name")" = "$(cat "$journal/$name.sha256")" ] || fail "File changed: $name"
            mv "/data/local/tmp/$name" "$journal/files/$name"
        done < "$journal/files-present"
        stop_loader
        state reboot-pending
        ;;
    commit)
        require_lock
        [ "$(cat "$journal/state")" = reboot-pending ] || fail 'Unexpected recovery state'
        [ "$(cat /proc/sys/kernel/random/boot_id)" != "$(cat "$journal/boot-before")" ] || fail 'Full reboot required'
        state verified
        release_lock
        ;;
    rollback)
        require_lock
        state restoring
        if [ -d "$journal/files" ]; then
            for name in $files; do
                if [ -f "$journal/files/$name" ]; then
                    ! exists "/data/local/tmp/$name" || fail "Original path occupied: $name"
                    mv "$journal/files/$name" "/data/local/tmp/$name"
                fi
            done
        fi
        if [ -f "$journal/startup-write-started" ]; then
            write_startup "$journal/startup"
            [ "$(digest "$startup")" = "$(cat "$journal/startup-before")" ] || fail 'Startup rollback failed'
        fi
        if [ -f "$journal/package-state" ]; then
            case "$(cat "$journal/package-state")" in
                absent) ;;
                0) pm default-state --user 0 "$package" ;;
                1) pm enable --user 0 "$package" ;;
                2) pm disable --user 0 "$package" ;;
                3) pm disable-user --user 0 "$package" ;;
                4) pm disable-until-used --user 0 "$package" ;;
                *) fail 'Original package state unavailable' ;;
            esac
        fi
        if [ -f "$journal/package-state" ] && [ "$(cat "$journal/package-state")" != absent ]; then
            restored_state=$(dumpsys package "$package" | sed -n 's/.*User 0:.* enabled=\([0-4]\).*/\1/p')
            [ "$restored_state" = "$(cat "$journal/package-state")" ] || fail 'Package state rollback failed'
        fi
        state rolled-back
        release_lock
        ;;
    *) fail 'Unknown recovery action' ;;
esac
