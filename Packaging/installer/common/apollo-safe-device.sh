#!/system/bin/sh
# Runs as root before replacing VoyahTune APKs. Only the retired stock-menu target is changed.
set -eu

restore=/data/user/0/ru.big.town.restoremode/shared_prefs/DrivePreferences.xml
native=/data/user/0/ru.big.town.anative/shared_prefs/NativePrefs.xml
backup=/data/local/voyahtune-apollo-backup
flag=/data/user_de/0/ru.big.town.anative/files/apollo_settings_runtime.v1

for key in open_voyah_apollo_master open_voyah_apollo_legacy_hook_enabled; do
    settings put global "$key" 0
    [ "$(settings get global "$key")" = 0 ] || exit 1
done

am force-stop ru.big.town.restoremode
am force-stop ru.big.town.anative

changed=0
for path in "$restore" "$restore.bak" "$native" "$native.bak"; do
    [ -e "$path" ] || continue
    [ -f "$path" ] && [ ! -L "$path" ] && [ -r "$path" ] && [ -w "$path" ] || exit 1
    case "$path" in
        "$restore"|"$restore.bak") key=apolloStockUiEnabled ;;
        *) key=cacheApolloStockUiEnabled ;;
    esac
    if grep -Fq "name=\"$key\" value=\"true\"" "$path"; then
        mkdir -p "$backup"
        chmod 700 "$backup"
        saved="$backup/$(basename "$path")"
        if [ ! -e "$saved" ]; then
            cp -p "$path" "$saved"
            chmod 600 "$saved"
        fi
        temp="$path.voyahtune-apollo-new"
        sed "s/name=\"$key\" value=\"true\"/name=\"$key\" value=\"false\"/g" "$path" > "$temp"
        [ -s "$temp" ] || exit 1
        cat "$temp" > "$path" # Keep the original PackageManager owner and file mode.
        rm -f "$temp"
        ! grep -Fq "name=\"$key\" value=\"true\"" "$path" || exit 1
        changed=1
    fi
done

rm -f "$flag" "$flag.new"
[ ! -e "$flag" ] || exit 1
if [ "$changed" = 1 ]; then
    am force-stop com.qinggan.app.vehiclesetting
fi
sync
echo "Apollo stock UI disabled; migrated=$changed"
