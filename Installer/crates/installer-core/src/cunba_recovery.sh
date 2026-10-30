set -eu
journal=/data/local/voyahtune-cunba-recovery
source_directory=/data/media/0/Download/cunba
saved_directory=$journal/directory
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

__STARTUP_BACKUP__

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

require_root() {
    [ "$(id -u)" = 0 ] || fail 'Root access required'
}

verify_rollback() {
    [ "$(cat "$journal/state")" = rolled-back ] || fail 'Recovery has not rolled back'
    for path in "$journal/startup" "$journal/startup-before" "$journal/package-state" "$startup"; do
        [ ! -L "$path" ] && [ -f "$path" ] || fail 'Rollback evidence missing or unsafe'
    done
    [ "$(digest "$journal/startup")" = "$(cat "$journal/startup-before")" ] || fail 'Startup backup changed'
    [ "$(digest "$startup")" = "$(cat "$journal/startup-before")" ] || fail 'Startup not restored'
    ! exists "$saved_directory" || fail 'Cunba directory still quarantined'
    ! exists "$journal/injector" || fail 'Injector still quarantined'
    if [ -f "$journal/directory-present" ]; then
        [ ! -L "$source_directory" ] && [ -d "$source_directory" ] || fail 'Cunba directory not restored'
    fi
    if [ -f "$journal/injector-before" ]; then
        [ ! -L "$injector" ] && [ -f "$injector" ] || fail 'Injector not restored'
        [ "$(digest "$injector")" = "$(cat "$journal/injector-before")" ] || fail 'Injector changed after rollback'
    fi
    packages=$(pm list packages --user 0) || fail 'Package Manager unavailable'
    printf '%s\n' "$packages" | grep -qx 'package:android' || fail 'Incomplete package inventory'
    original_state=$(cat "$journal/package-state")
    case "$original_state" in
        absent)
            if printf '%s\n' "$packages" | grep -qx "package:$package"; then
                fail 'Package appeared after rollback'
            fi
            ;;
        0|1|2|3|4)
            printf '%s\n' "$packages" | grep -qx "package:$package" || fail 'Package not restored'
            package_dump=$(dumpsys package "$package") || fail 'Package state unavailable'
            restored_state=$(printf '%s\n' "$package_dump" | sed -n 's/.*User 0:.* enabled=\([0-4]\).*/\1/p')
            [ "$restored_state" = "$original_state" ] || fail 'Package state not restored'
            ;;
        *) fail 'Original package state unavailable' ;;
    esac
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

apply_changes() {
    [ "$(digest "$startup")" = "$(cat "$journal/startup-before")" ] || fail 'Startup changed after inspection'
    backup_startup
    state applying
    if [ "$(cat "$journal/package-state")" != absent ]; then
        pm disable-user --user 0 "$package"
        pm list packages -d --user 0 | grep -qx "package:$package" || fail 'Package was not disabled'
    fi
    stop_processes
    if [ -f "$journal/startup" ]; then
        [ "$(digest "$startup")" = "$(cat "$journal/startup-before")" ] || fail 'Startup changed after inspection'
        : > "$journal/startup-write-started"
        sync
        replace_startup "$journal/startup-disabled"
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
}

require_root
[ ! -L "$journal" ] || fail 'Recovery journal is a symbolic link'
case "$action" in
    archive-rollback)
        verify_rollback
        history=/data/local/voyahtune-cunba-history
        [ ! -L "$history" ] || fail 'Recovery history is a symbolic link'
        if ! exists "$history"; then mkdir -m 700 "$history"; fi
        [ -d "$history" ] || fail 'Recovery history is not a directory'
        archive=$(mktemp -d "$history/attempt.XXXXXX")
        mv "$journal" "$archive/journal"
        sync
        ;;
    prepare)
        if [ -d "$journal" ]; then
            fail 'Previous recovery exists; restore or verify it first'
        fi
        [ ! -L "$source_directory" ] && [ ! -L "$injector" ] && [ ! -L "$startup" ] || fail 'Unsupported symbolic link'
        [ ! -L /data/media/0/Download ] && [ ! -L /data/local/bin ] || fail 'Unsupported parent link'
        ! exists "$saved_directory" || fail 'Quarantine already exists'
        if exists "$source_directory/patch/load.bin"; then
            [ ! -L "$source_directory/patch" ] && [ ! -L "$source_directory/patch/load.bin" ] || fail 'Unsupported loader link'
            [ -f "$source_directory/patch/load.bin" ] || fail 'Loader is not a regular file'
        fi
        if exists "$injector"; then
            [ -f "$injector" ] || fail 'Injector is not a regular file'
        fi
        [ -f "$startup" ] || fail 'Startup script missing'
        packages=$(pm list packages --user 0) || fail 'Package Manager unavailable'
        printf '%s\n' "$packages" | grep -qx 'package:android' || fail 'Incomplete package inventory'
        package_state=absent
        if printf '%s\n' "$packages" | grep -qx "package:$package"; then
            package_dump=$(dumpsys package "$package") || fail 'Package state unavailable'
            package_state=$(printf '%s\n' "$package_dump" | sed -n 's/.*User 0:.* enabled=\([0-4]\).*/\1/p')
            case "$package_state" in 0|1|2|3|4) ;; *) fail 'Unknown package enabled state' ;; esac
        fi
        mkdir "$journal"
        chmod 700 "$journal"
        printf '%s\n' "$package_state" > "$journal/package-state"
        cat /proc/sys/kernel/random/boot_id > "$journal/boot-before"
        state preparing
        cp -p "$startup" "$journal/startup"
        digest "$startup" > "$journal/startup-before"
        [ "$(digest "$journal/startup")" = "$(cat "$journal/startup-before")" ] || fail 'Startup backup verification failed'
        ls -Zd "$startup" | awk '{print $1}' > "$journal/startup-context"
        cat > "$journal/startup-disabled" <<'VOYAHTUNE_CLEAN_STARTUP'
__CUNBA_CLEAN_STARTUP__
VOYAHTUNE_CLEAN_STARTUP
        /system/bin/sh -n "$journal/startup-disabled"
        digest "$journal/startup-disabled" > "$journal/startup-after"
        if [ -d "$source_directory" ]; then : > "$journal/directory-present"; fi
        if [ -f "$injector" ]; then digest "$injector" > "$journal/injector-before"; fi
        state prepared
        ;;
    apply)
        [ "$(cat "$journal/state")" = prepared ] || fail 'Recovery not prepared'
        with_writable_system apply_changes
        ;;
    commit)
        [ "$(cat "$journal/state")" = reboot-pending ] || fail 'Unexpected recovery state'
        [ "$(cat /proc/sys/kernel/random/boot_id)" != "$(cat "$journal/boot-before")" ] || fail 'Full reboot required'
        state verified
        ;;
    rollback)
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
                [ "$(digest "$journal/startup")" = "$(cat "$journal/startup-before")" ] || fail 'Startup backup changed'
                with_writable_system replace_startup "$journal/startup"
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
        ;;
    *) fail 'Unknown recovery action' ;;
esac
