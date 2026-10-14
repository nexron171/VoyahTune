set -eu
journal=/data/local/voyahtune-cunba-recovery
lock=/data/local/voyahtune-install.lock
source_directory=/data/media/0/Download/cunba
saved_directory=$journal/directory
injector=/data/local/bin/frida-inject2
startup=/system/etc/init.logcat.sh
package=com.cunba.lantu.otan.motorstore

fail() { echo "$*" >&2; exit 1; }
exists() { [ -e "$1" ] || [ -L "$1" ]; }
digest() { sha256sum "$1" | cut -d ' ' -f 1; }
state() { printf '%s\n' "$1" > "$journal/state.new"; mv "$journal/state.new" "$journal/state"; sync; }
require_root() { [ "$(id -u)" = 0 ] || fail 'Root access required'; }
require_lock() {
    [ ! -L "$lock" ] && [ -d "$lock" ] || fail 'Installation lock missing'
    [ "$(cat "$lock/owner")" = 'desktop:cunba-recovery' ] || fail 'Installation lock belongs to another operation'
}
release_lock() {
    require_lock
    rm "$lock/owner" "$lock/boot"
    rmdir "$lock"
    sync
}
stop_processes() {
    # Only this loader and this exact executable belong to the supported recipe.
    for process in /proc/[0-9]*; do
        [ -r "$process/cmdline" ] || continue
        command=$(tr '\000' ' ' < "$process/cmdline" 2>/dev/null) || continue
        case "$command" in
            'sh /sdcard/Download/cunba/patch/load.bin ' | '/system/bin/sh /sdcard/Download/cunba/patch/load.bin ' | \
            'sh /storage/emulated/0/Download/cunba/patch/load.bin ' | '/system/bin/sh /storage/emulated/0/Download/cunba/patch/load.bin ' | \
            'sh /data/media/0/Download/cunba/patch/load.bin ' | '/system/bin/sh /data/media/0/Download/cunba/patch/load.bin ')
                identity=$(cat "$process/stat" 2>/dev/null) || continue
                [ "$(cat "$process/stat" 2>/dev/null | cut -d ' ' -f 22)" = "$(printf '%s' "$identity" | cut -d ' ' -f 22)" ] || continue
                kill -STOP "${process##*/}" 2>/dev/null || true
                kill -KILL "${process##*/}" 2>/dev/null || true
                ;;
        esac
    done
    for process in /proc/[0-9]*; do
        executable=$(readlink "$process/exe" 2>/dev/null) || continue
        case "$executable" in "$injector" | "$journal/injector")
            [ "$(readlink "$process/exe" 2>/dev/null)" = "$executable" ] || continue
            kill -TERM "${process##*/}" 2>/dev/null || true
            ;;
        esac
    done
}
require_root
[ ! -L "$journal" ] || fail 'Recovery journal is a symbolic link'
case "$action" in
    prepare)
        if [ -d "$journal" ]; then
            fail 'Previous recovery exists; restore or verify it first'
        fi
        [ ! -L "$source_directory" ] && [ ! -L "$injector" ] && [ ! -L "$startup" ] || fail 'Unsupported symbolic link'
        [ ! -L /data/media/0/Download ] && [ ! -L /data/local/bin ] || fail 'Unsupported parent link'
        ! exists "$saved_directory" || fail 'Quarantine already exists'
        if [ -e "$source_directory/patch/load.bin" ]; then
            [ ! -L "$source_directory/patch" ] && [ ! -L "$source_directory/patch/load.bin" ] || fail 'Unsupported loader link'
            [ "$(digest "$source_directory/patch/load.bin")" = '8470f74751ceebff7697a12fadd11d4bd213b8c630549a19f2c739c140118fc2' ] || fail 'Unknown Cunba loader; automatic changes are not supported'
        fi
        if exists "$injector"; then
            [ "$(digest "$injector")" = '8906e493dac0f92220710634e412ec03f564ff32a24d49961fda1930f3e9e8f8' ] || fail 'Unknown frida-inject2; automatic changes are not supported'
        fi
        # A shared OEM startup script is editable only when every matching line is understood.
        if [ -f "$startup" ] && grep -Eiq 'cunba|frida-inject2|launcher2[.]bin|systemserver[.]bin' "$startup"; then
            awk '
                tolower($0) ~ /cunba|frida-inject2|launcher2[.]bin|systemserver[.]bin/ {
                    if ($0 !~ /^[ \t]*(\/system\/bin\/sh|sh) \/sdcard\/Download\/cunba\/patch\/load[.]bin[ \t]*&?[ \t]*$/) exit 1
                }
            ' "$startup" || fail 'Unknown Cunba startup commands; automatic changes are not supported'
        fi
        packages=$(pm list packages --user 0) || fail 'Package Manager unavailable'
        printf '%s\n' "$packages" | grep -qx 'package:android' || fail 'Incomplete package inventory'
        package_state=absent
        if printf '%s\n' "$packages" | grep -qx "package:$package"; then
            package_dump=$(dumpsys package "$package") || fail 'Package state unavailable'
            package_state=$(printf '%s\n' "$package_dump" | sed -n 's/.*User 0:.* enabled=\([0-4]\).*/\1/p')
            case "$package_state" in 0|1|2|3|4) ;; *) fail 'Unknown package enabled state' ;; esac
        fi
        mkdir "$lock" 2>/dev/null || fail 'Another installation or OTA owns the device'
        printf '%s' 'desktop:cunba-recovery' > "$lock/owner"
        cat /proc/sys/kernel/random/boot_id > "$lock/boot"
        chmod 700 "$lock"
        # If preparation fails before a journal exists, do not strand our lock.
        trap '[ -d "$journal" ] || release_lock' EXIT
        mkdir "$journal"
        chmod 700 "$journal"
        printf '%s\n' "$package_state" > "$journal/package-state"
        cat /proc/sys/kernel/random/boot_id > "$journal/boot-before"
        state preparing
        if [ -f "$startup" ] && grep -Fq '/cunba/patch/load.bin' "$startup"; then
            cp -p "$startup" "$journal/startup"
            digest "$startup" > "$journal/startup-before"
            awk '!/\/cunba\/patch\/load[.]bin/' "$startup" > "$journal/startup-disabled"
            /system/bin/sh -n "$journal/startup-disabled"
            digest "$journal/startup-disabled" > "$journal/startup-after"
        fi
        if [ -d "$source_directory" ]; then : > "$journal/directory-present"; fi
        if [ -f "$injector" ]; then digest "$injector" > "$journal/injector-before"; fi
        state prepared
        trap - EXIT
        ;;
    apply)
        require_lock
        [ "$(cat "$journal/state")" = prepared ] || fail 'Recovery not prepared'
        state applying
        if [ "$(cat "$journal/package-state")" != absent ]; then
            pm disable-user --user 0 "$package"
            pm list packages -d --user 0 | grep -qx "package:$package" || fail 'Package was not disabled'
        fi
        stop_processes
        if [ -f "$journal/startup" ]; then
            [ "$(digest "$startup")" = "$(cat "$journal/startup-before")" ] || fail 'Startup changed after inspection'
            # Write through the existing inode to preserve ownership, mode and SELinux label.
            : > "$journal/startup-write-started"
            sync
            cat "$journal/startup-disabled" > "$startup"
            [ "$(digest "$startup")" = "$(cat "$journal/startup-after")" ] || fail 'Startup verification failed'
        fi
        if [ -f "$journal/directory-present" ]; then
            ! exists "$saved_directory" || fail 'Quarantine appeared during operation'
            mv "$source_directory" "$saved_directory"
        fi
        if [ -f "$journal/injector-before" ]; then
            [ "$(digest "$injector")" = "$(cat "$journal/injector-before")" ] || fail 'Injector changed after inspection'
            mv "$injector" "$journal/injector"
        fi
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
        if [ -f "$journal/injector" ]; then
            if exists "$injector"; then
                [ "$(digest "$injector")" = "$(cat "$journal/injector-before")" ] || fail 'New injector occupies original path; backup retained'
                rm "$injector"
            fi
            mv "$journal/injector" "$injector"
        fi
        if [ -f "$journal/directory-present" ] && exists "$saved_directory"; then
            # Preserve regenerated files as evidence instead of destroying user data.
            if exists "$source_directory"; then
                ! exists "$journal/regenerated-directory" || fail 'Regenerated directory already saved'
                mv "$source_directory" "$journal/regenerated-directory"
            fi
            mv "$saved_directory" "$source_directory"
        fi
        if [ -f "$journal/startup-write-started" ]; then
            if [ "$(digest "$startup")" != "$(cat "$journal/startup-before")" ]; then
                cat "$journal/startup" > "$startup"
            fi
            [ "$(digest "$startup")" = "$(cat "$journal/startup-before")" ] || fail 'Startup rollback verification failed'
        fi
        case "$(cat "$journal/package-state")" in
            absent) ;;
            0) pm default-state --user 0 "$package" ;;
            1) pm enable --user 0 "$package" ;;
            2) pm disable --user 0 "$package" ;;
            3) pm disable-user --user 0 "$package" ;;
            4) pm disable-until-used --user 0 "$package" ;;
            *) fail 'Original package state unavailable' ;;
        esac
        if [ "$(cat "$journal/package-state")" != absent ]; then
            restored_state=$(dumpsys package "$package" | sed -n 's/.*User 0:.* enabled=\([0-4]\).*/\1/p')
            [ "$restored_state" = "$(cat "$journal/package-state")" ] || fail 'Package state rollback failed'
        fi
        state rolled-back
        release_lock
        ;;
    *) fail 'Unknown recovery action' ;;
esac
