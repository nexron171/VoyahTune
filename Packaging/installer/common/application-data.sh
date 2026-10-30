#!/system/bin/sh
set -eu

case "$application_package" in
    ru.big.town.anative|ru.big.town.restoremode) ;;
    *) echo 'Unsupported application data package' >&2; exit 1 ;;
esac

backup_root=/data/local/voyahtune-application-backups
backup="$backup_root/$application_package"

fail_data() {
    echo "Application data: $*; backup: $backup" >&2
    exit 1
}

check_backup() {
    [ -f "$backup/complete" ] || fail_data 'incomplete backup'
    (cd "$backup" && sha256sum -c checksums) || fail_data 'damaged backup'
}

save_directory() {
    source_directory=$1
    archive_name=$2
    [ ! -L "$source_directory" ] || fail_data "symlink: $source_directory"
    [ -d "$source_directory" ] || return 0
    [ ! -f "$pending/$archive_name.tar" ] || return 0
    metadata=$(stat -c '%a:%u:%g' "$source_directory")
    printf '%s\n' "$metadata" > "$pending/$archive_name.owner"
    tar -cpf "$pending/$archive_name.tar" -C "$source_directory" .
    tar -tf "$pending/$archive_name.tar" >/dev/null
}

backup_data() {
    [ ! -L "$backup_root" ] && [ ! -L "$backup" ] || fail_data 'symlink backup path'
    if [ -d "$backup" ]; then
        check_backup
        return
    fi
    umask 077
    mkdir -p "$backup_root"
    chmod 700 "$backup_root"
    pending="$backup.pending"
    [ ! -L "$pending" ] || fail_data 'symlink pending backup'
    rm -rf "$pending"
    mkdir "$pending"
    for adopted in /mnt/expand/*/user/*/"$application_package" /mnt/expand/*/user_de/*/"$application_package"; do
        [ ! -d "$adopted" ] || fail_data 'adopted application storage is not supported'
    done
    am force-stop --user all "$application_package"
    for user_directory in /data/user/* /data/user_de/*; do
        [ -d "$user_directory" ] || continue
        user=${user_directory##*/}
        case "$user" in *[!0-9]*|'') fail_data 'invalid user directory' ;; esac
        # An inaccessible CE directory must never be mistaken for missing data.
        [ -d "/data/user/$user/$application_package" ] || {
            [ ! -d "/data/user_de/$user/$application_package" ] || fail_data "CE unavailable for user $user"
            continue
        }
        [ "$(am get-started-user-state "$user")" = RUNNING_UNLOCKED ] || fail_data "user $user is locked"
        save_directory "/data/user/$user/$application_package" "ce-$user"
        save_directory "/data/user_de/$user/$application_package" "de-$user"
        for area in data media obb; do
            save_directory "/data/media/$user/Android/$area/$application_package" "$area-$user"
        done
    done
    touch "$pending/complete"
    (cd "$pending" && {
        sha256sum complete
        for file in *.tar *.owner; do
            [ -f "$file" ] || continue
            sha256sum "$file"
        done
    } > checksums)
    sync
    mv "$pending" "$backup"
    sync
}

restore_directory() {
    archive=$1
    name=${archive##*/}
    name=${name%.tar}
    area=${name%-*}
    user=${name##*-}
    case "$user" in *[!0-9]*|'') fail_data 'invalid backup user' ;; esac
    case "$area" in
        ce) destination="/data/user/$user/$application_package" ;;
        de) destination="/data/user_de/$user/$application_package" ;;
        data|media|obb) destination="/data/media/$user/Android/$area/$application_package" ;;
        *) fail_data 'invalid backup area' ;;
    esac
    # Read Android's newly assigned UID before copying the original ownership.
    new_uid=$(pm list packages -U --user "$user" "$application_package" |
        sed -n "s/^package:$application_package uid://p")
    case "$new_uid" in *[!0-9]*|'') fail_data "missing UID for user $user" ;; esac
    previous=$(cat "$backup/ce-$user.owner")
    previous=${previous#*:}
    old_uid=${previous%%:*}
    [ ! -L "$destination" ] || fail_data "symlink: $destination"
    mkdir -p "$destination"
    find "$destination" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
    tar -xpf "$archive" -C "$destination"
    if [ "$old_uid" != "$new_uid" ]; then
        find "$destination" -user "$old_uid" -exec chown -h "$new_uid" {} +
        find "$destination" -group "$old_uid" -exec chown -h ":$new_uid" {} +
        find "$destination" -group "$((old_uid + 10000))" -exec chown -h ":$((new_uid + 10000))" {} +
    fi
    restorecon -RF "$destination"
}

restore_data() {
    [ ! -L "$backup_root" ] && [ ! -L "$backup" ] || fail_data 'symlink backup path'
    [ -d "$backup" ] || return 0
    check_backup
    rm -f "$backup/restored"
    pm path "$application_package" | grep -q '^package:' || fail_data 'application is not installed'
    for archive in "$backup"/ce-*.tar; do
        [ -f "$archive" ] || continue
        user=${archive##*/ce-}
        user=${user%.tar}
        case "$user" in *[!0-9]*|'') fail_data 'invalid backup user' ;; esac
        [ "$(am get-started-user-state "$user")" = RUNNING_UNLOCKED ] || fail_data "user $user is locked"
        registered=$(pm list packages -U --user "$user" "$application_package")
        if ! printf '%s\n' "$registered" | grep -q "^package:$application_package uid:"; then
            cmd package install-existing --user "$user" "$application_package"
        fi
    done
    am force-stop --user all "$application_package"
    for archive in "$backup"/*.tar; do
        [ -f "$archive" ] || continue
        restore_directory "$archive"
    done
    touch "$backup/restored"
    sync
}

cleanup_data() {
    [ ! -L "$backup_root" ] && [ ! -L "$backup" ] || fail_data 'symlink backup path'
    if [ -d "$backup" ]; then
        [ -f "$backup/restored" ] || fail_data 'data has not been restored'
        rm -rf "$backup.restored"
        mv "$backup" "$backup.restored"
        sync
    fi
    rm -rf "$backup.pending" "$backup.restored"
    if [ -d "$backup_root" ] && [ -z "$(ls -A "$backup_root")" ]; then
        rmdir "$backup_root"
    fi
    sync
}

case "$application_data_action" in
    backup) backup_data ;;
    restore) restore_data ;;
    cleanup) cleanup_data ;;
    *) fail_data 'unsupported action' ;;
esac
