#!/system/bin/sh
set -eu

pattern='cunba|voyahtweaks|frida-inject2|launcher2[.]bin|systemserver[.]bin|/data/local/tmp/(load|launcher|ss11|ss9)[.]bin'

disable_path() {
    [ -e "$1" ] || [ -L "$1" ] || return 0
    destination=$1.voyahtune-disabled
    suffix=0
    while [ -e "$destination" ] || [ -L "$destination" ]; do
        suffix=$((suffix + 1))
        destination=$1.voyahtune-disabled.$suffix
    done
    mv "$1" "$destination"
}

# Stop package watchdogs before moving their startup files.
packages=$(pm list packages --user 0)
while IFS= read -r entry; do
    package=${entry#package:}
    case "$(printf '%s' "$package" | tr '[:upper:]' '[:lower:]')" in
        *cunba*|*voyahtweaks*)
            am force-stop --user 0 "$package" || true
            pm disable-user --user 0 "$package"
            ;;
    esac
done <<PACKAGES
$packages
PACKAGES

for startup in /system/etc/init*.sh /system/etc/init/*.rc /system/etc/init/*.sh \
    /vendor/etc/init/*.rc /vendor/etc/init/*.sh /odm/etc/init/*.rc \
    /product/etc/init/*.rc /system_ext/etc/init/*.rc /data/adb/service.d/* \
    /data/adb/post-fs-data.d/* /data/local/userinit.sh /data/local/userinit.d/*; do
    [ -f "$startup" ] || continue
    case "$startup" in
        /system/etc/init.logcat.sh|*.voyahtune-disabled*) continue ;;
    esac
    dedicated=0
    if printf '%s\n' "$startup" | grep -Eiq "$pattern"; then
        dedicated=1
    fi
    if [ "$dedicated" = 0 ] && ! grep -Eiq "$pattern" "$startup"; then
        continue
    fi
    case "$startup" in
        /vendor/*) mount -o rw,remount /vendor 2>/dev/null || true ;;
        /odm/*) mount -o rw,remount /odm 2>/dev/null || true ;;
        /product/*) mount -o rw,remount /product 2>/dev/null || true ;;
        /system_ext/*) mount -o rw,remount /system_ext 2>/dev/null || true ;;
    esac
    case "$startup" in
        *.rc)
            # Preserve unrelated Android services and actions in shared init files.
            services=$(awk -v pattern="$pattern" -v dedicated="$dedicated" '
                $1 == "service" && (dedicated || tolower($0) ~ pattern) { print $2 }
            ' "$startup")
            for service in $services; do
                setprop ctl.stop "$service" || true
            done
            if [ "$dedicated" = 1 ]; then
                disable_path "$startup"
                continue
            fi
            replacement=$startup.voyahtune-new
            rm -f "$replacement"
            cp -p "$startup" "$replacement"
            awk -v pattern="$pattern" '
                /^[^[:space:]#]/ { skip = ($1 == "service" && tolower($0) ~ pattern) }
                !skip && tolower($0) !~ pattern { print }
            ' "$startup" > "$replacement"
            mv -f "$replacement" "$startup"
            restorecon "$startup"
            ;;
        *)
            # Keep backups outside directories traversed by boot runners.
            backup=/data/local/voyahtune-disabled-loaders$startup
            mkdir -p "${backup%/*}"
            if [ ! -e "$backup" ]; then
                cp -p "$startup" "$backup"
            fi
            printf '#!/system/bin/sh\n:\n' > "$startup"
            ;;
    esac
done

for location in /sdcard/Download/cunba /storage/emulated/0/Download/cunba \
    /data/media/0/Download/cunba /data/local/bin/frida-inject2 \
    /system/bin/frida-inject2 /system/xbin/frida-inject2; do
    disable_path "$location"
done
for name in load.bin frida-inject launcher.bin launcher.js keymanager.bin keymanager.js \
    rus.bin rus.js ss11.bin ss11.js ss9.bin ss9.js instrcard.bin instrcard.js \
    virtualdisplay.bin virtualdisplay.js complex.bin complex.js login.bin login.js; do
    disable_path "/data/local/tmp/$name"
done

for process in /proc/[0-9]*; do
    process_id=${process##*/}
    [ "$process_id" != "$$" ] && [ "$process_id" != "$PPID" ] || continue
    [ -r "$process/cmdline" ] || continue
    arguments=$(tr '\000' ' ' < "$process/cmdline" 2>/dev/null) || continue
    # Match argv, not the text of a shell script delivered over ADB.
    case "$arguments" in *' -c '*|*' -s '*) continue ;; esac
    if printf '%s\n' "$arguments" | grep -Eiq "$pattern"; then
        kill "$process_id" 2>/dev/null || true
    fi
done
sync
