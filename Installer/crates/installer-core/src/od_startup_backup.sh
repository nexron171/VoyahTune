has_foreign_startup() {
    if grep -Eiq 'voyahtweaks|/data/local/tmp/(load|launcher|ss11|ss9)[.]bin|cunba|frida-inject2|launcher2[.]bin|systemserver[.]bin' "$startup"; then
        return 0
    else
        status=$?
        [ "$status" = 1 ] || fail 'Startup inspection failed'
        return 1
    fi
}

backup_startup() (
    [ -f "$startup" ] && [ ! -L "$startup" ] || fail 'Unsafe startup backup source'
    timestamp=$(date '+%Y-%m-%d_%H:%M:%S')
    backup=${startup}_${timestamp}_backup
    suffix=0
    while exists "$backup"; do
        suffix=$((suffix + 1))
        backup=${startup}_${timestamp}_${suffix}_backup
    done

    # Reserve without following links or overwriting an earlier backup.
    (set -C; : > "$backup") || fail 'Cannot reserve startup backup'
    cp -p "$startup" "$backup" || fail 'Cannot copy startup backup'
    [ "$(digest "$backup")" = "$(cat "$journal/startup-before")" ] || fail 'Startup backup verification failed'
    printf '%s\n' "$backup" > "$journal/startup-backup-path"
    sync
    printf 'STARTUP_BACKUP\t%s\n' "$backup"
)
