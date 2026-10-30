set -eu
journal=/data/local/voyahtune-cunba-recovery
source_directory=/data/media/0/Download/cunba
injector=/data/local/bin/frida-inject2
startup=/system/etc/init.logcat.sh
package=com.cunba.lantu.otan.motorstore
fail() {
    echo "$*" >&2
    exit 1
}

exists() {
    [ -e "$1" ] || [ -L "$1" ]
}

digest() {
    sha256sum "$1" | cut -d ' ' -f 1
}

__CUNBA_SYSTEM_ACCESS__

replace_startup() (
    replacement=$startup.voyahtune-cunba-new
    [ ! -L "$startup" ] && [ -f "$startup" ] || fail 'Unsafe startup target'
    [ ! -L "$replacement" ] || fail 'Unsafe startup replacement'
    if exists "$replacement"; then
        [ -f "$replacement" ] || fail 'Unsafe startup replacement'
        rm "$replacement"
    fi
    trap 'rm -f "$replacement"' EXIT
    cp -p "$journal/startup" "$replacement"
    cat "$1" > "$replacement"
    context=$(cat "$journal/startup-context" 2>/dev/null || ls -Zd "$startup" | awk '{print $1}')
    case "$context" in u:object_r:*:*) ;; *) fail 'Startup SELinux context unavailable' ;; esac
    chcon "$context" "$replacement"
    [ "$(digest "$replacement")" = "$(digest "$1")" ] || fail 'Startup replacement verification failed'
    sync
    mv -f "$replacement" "$startup"
    sync
)

state() {
    printf '%s\n' "$1" > "$journal/state.new"
    mv "$journal/state.new" "$journal/state"
    sync
}

package_state() { dumpsys package "$package" | sed -n 's/.*User 0:.* enabled=\([0-4]\).*/\1/p'; }

require_backup() {
    [ ! -L "$journal" ] && [ -d "$journal" ] || fail 'Cunba backup unavailable'
    case "$(cat "$journal/state")" in verified|reactivating|restore-reboot-pending) ;; *) fail 'Cunba backup is not available for restoration' ;; esac
    case "$(cat "$journal/package-state")" in absent|0|1|2|3|4) ;; *) fail 'Invalid package state backup' ;; esac
    if [ -f "$journal/directory-present" ]; then
        if [ -f "$journal/restore-directory-started" ] && [ -d "$source_directory" ]; then :
        else [ -d "$journal/directory" ] && [ ! -L "$journal/directory" ] || fail 'Cunba directory backup missing'; fi
    fi
    if [ -f "$journal/injector-before" ]; then
        [ -f "$journal/injector" ] && [ ! -L "$journal/injector" ] || fail 'Injector backup missing'
        [ "$(digest "$journal/injector")" = "$(cat "$journal/injector-before")" ] || fail 'Injector backup changed'
    fi
    if [ -f "$journal/startup-write-started" ]; then
        [ -f "$journal/startup" ] && [ ! -L "$journal/startup" ] || fail 'Startup backup missing'
        [ "$(digest "$journal/startup")" = "$(cat "$journal/startup-before")" ] || fail 'Startup backup changed'
    fi
}

require_removed() {
    packages=$(pm list packages --user 0) || fail 'Package Manager unavailable'
    printf '%s\n' "$packages" | grep -qx 'package:android' || fail 'Incomplete package inventory'
    if printf '%s\n' "$packages" | grep -Eq '^package:ru[.]big[.]town[.](anative|restoremode|updater)$'; then
        fail 'VoyahTune applications are still installed'
    fi
    for path in /data/local/bin/voyahtune-updater /data/local/bin/voyahtune-hook-manifest.json /data/local/bin/loaderFrida /system/priv-app/ANative; do
        ! exists "$path" || fail "VoyahTune component remains: $path"
    done
    processes=$(ps -A -o PID,ARGS) || fail 'Process inventory unavailable'
    printf '%s\n' "$processes" | grep -Eq '^[[:space:]]*[0-9]+[[:space:]]+' || fail 'Incomplete process inventory'
    if printf '%s\n' "$processes" | grep -Eq 'frida-inject([[:space:]]|$)|voyahtune-updater|ru[.]big[.]town[.](anative|restoremode)'; then
        fail 'VoyahTune processes are still running'
    fi
}

verify_files() {
    if [ -f "$journal/directory-present" ]; then
        [ -d "$source_directory" ] && [ ! -L "$source_directory" ] || fail 'Cunba directory not restored'
    fi
    if [ -f "$journal/injector-before" ]; then
        [ ! -L "$injector" ] && [ "$(digest "$injector")" = "$(cat "$journal/injector-before")" ] || fail 'Injector not restored'
    fi
    if [ -f "$journal/restore-startup-after" ]; then
        [ ! -L "$startup" ] && [ "$(digest "$startup")" = "$(cat "$journal/restore-startup-after")" ] || fail 'Startup not restored'
    fi
    if [ "$(cat "$journal/package-state")" != absent ]; then
        [ "$(package_state)" = "$(cat "$journal/package-state")" ] || fail 'Package state not restored'
    fi
}

[ "$(id -u)" = 0 ] || fail 'Root access required'
case "$action" in
    available)
        if (require_backup) >/dev/null 2>&1; then echo RESTORE_AVAILABLE; else echo RESTORE_UNAVAILABLE; fi
        ;;
    removed) require_removed ;;
    restore)
        require_backup
        require_removed
        if [ -f "$startup" ] && grep -Eiq 'voyahtune|open.voyah|frida-inject[^2]|/data/local/bin/load[.]bin|loaderFrida' "$startup"; then
            fail 'VoyahTune startup commands remain'
        fi
        [ ! -L "$source_directory" ] && [ ! -L "$injector" ] && [ ! -L "$startup" ] || fail 'Unsupported target link'
        [ ! -L /data/media/0/Download ] && [ ! -L /data/local/bin ] || fail 'Unsupported parent link'
        if exists "$source_directory" && [ ! -f "$journal/restore-directory-started" ]; then fail 'Cunba directory already occupies target'; fi
        if exists "$injector"; then
            [ -f "$journal/injector-before" ] && [ "$(digest "$injector")" = "$(cat "$journal/injector-before")" ] || fail 'Another injector occupies target'
        fi
        state reactivating
        if [ -f "$journal/startup-write-started" ]; then
            [ -f "$startup" ] || fail 'Current startup script missing'
            if [ ! -f "$journal/restore-startup-after" ]; then
                cp -p "$startup" "$journal/restore-startup-before"
                cp -p "$journal/startup" "$journal/restore-startup-new"
                digest "$journal/restore-startup-new" > "$journal/restore-startup-after"
                sync
            fi
            current_hash=$(digest "$startup")
            if [ "$current_hash" != "$(cat "$journal/restore-startup-after")" ]; then
                [ "$current_hash" = "$(digest "$journal/restore-startup-before")" ] || fail 'Startup changed during restoration'
                with_writable_system replace_startup "$journal/restore-startup-new"
            fi
        fi
        if [ -f "$journal/directory-present" ] && [ -d "$journal/directory" ]; then
            ! exists "$source_directory" || fail 'Cunba directory occupies target; backup retained'
            : > "$journal/restore-directory-started"
            sync
            mv "$journal/directory" "$source_directory"
        fi
        if [ -f "$journal/injector-before" ]; then cp -p "$journal/injector" "$injector"; fi
        case "$(cat "$journal/package-state")" in
            absent) ;;
            0) pm default-state --user 0 "$package" ;;
            1) pm enable --user 0 "$package" ;;
            2) pm disable --user 0 "$package" ;;
            3) pm disable-user --user 0 "$package" ;;
            4) pm disable-until-used --user 0 "$package" ;;
        esac
        verify_files
        cat /proc/sys/kernel/random/boot_id > "$journal/restore-boot-before"
        state restore-reboot-pending
        ;;
    verify)
        require_backup
        require_removed
        [ "$(cat "$journal/state")" = restore-reboot-pending ] || fail 'Restoration has not completed'
        [ "$(cat /proc/sys/kernel/random/boot_id)" != "$(cat "$journal/restore-boot-before")" ] || fail 'Full reboot required'
        verify_files
        ;;
    finish)
        require_backup
        verify_files
        [ "$(cat "$journal/state")" = restore-reboot-pending ] || fail 'Unexpected restore state'
        [ "$(cat /proc/sys/kernel/random/boot_id)" != "$(cat "$journal/restore-boot-before")" ] || fail 'Full reboot required'
        state restored
        ;;
    *) fail 'Unknown restore action' ;;
esac
