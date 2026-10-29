#!/system/bin/sh
set -eu
BIN_DIRECTORY=${VOYAHTUNE_BIN_DIRECTORY:-/data/local/bin}
BOOT_SOURCE=${VOYAHTUNE_BOOT_SOURCE:-/proc/sys/kernel/random/boot_id}
[ ! -L "$BIN_DIRECTORY" ] || exit 1
mkdir -p "$BIN_DIRECTORY"
umask 077
block="$BIN_DIRECTORY/voyahtune-update.block"
marker="$BIN_DIRECTORY/voyahtune-native-migration.boot"
[ ! -L "$block" ] && [ ! -L "$marker" ] || exit 1
stage="$BIN_DIRECTORY/.voyahtune-maintenance.$$.new"
[ ! -e "$stage" ] && [ ! -L "$stage" ] || exit 1
trap 'rm -f "$stage"' EXIT
(set -C; printf '%s\n' 'Runtime maintenance; USB repair required if interrupted' > "$stage")
chown 0:0 "$stage"
chmod 600 "$stage"
mv -f "$stage" "$block"
sync
boot=$(cat "$BOOT_SOURCE") || exit 1
case "$boot" in ''|*[!A-Za-z0-9-]*) exit 1 ;; esac
(set -C; printf '%s\n' "$boot" > "$stage")
chown 0:0 "$stage"
chmod 600 "$stage"
mv -f "$stage" "$marker"
sync
