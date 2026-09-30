#!/bin/bash
# Emergency Apollo opt-out for VoyahTune on a parked vehicle (Android 11).
# Run on macOS with one connected, authorized ADB device and adbd root access.
set -euo pipefail

adb_bin=${ADB_BIN:-adb}
restore_prefs=/data/user/0/ru.big.town.restoremode/shared_prefs/DrivePreferences.xml
native_prefs=/data/user/0/ru.big.town.anative/shared_prefs/NativePrefs.xml

command -v "$adb_bin" >/dev/null || { echo "ADB не найден: $adb_bin" >&2; exit 1; }

device_lines=$("$adb_bin" devices | awk 'NR > 1 && NF >= 2 { print $1, $2 }')
device_count=$(printf '%s\n' "$device_lines" | awk 'NF { count++ } END { print count+0 }')
if [ "$device_count" -ne 1 ] || [ "$(printf '%s\n' "$device_lines" | awk '{ print $2 }')" != device ]; then
    echo "Нужно ровно одно авторизованное устройство в состоянии device. Сейчас:" >&2
    printf '%s\n' "$device_lines" >&2
    exit 1
fi
serial=$(printf '%s\n' "$device_lines" | awk '{ print $1 }')
adb() { "$adb_bin" -s "$serial" "$@"; }

adb root >/dev/null
adb wait-for-device
if [ "$(adb shell id -u | tr -d '\r')" != 0 ]; then
    echo "adbd не получил root; настройки приложений не менялись." >&2
    exit 1
fi
if ! adb shell "test -f '$restore_prefs'"; then
    echo "Не найден $restore_prefs; версия или пользователь Android отличаются. Настройки не менялись." >&2
    exit 1
fi

backup_dir=$(mktemp -d "${TMPDIR:-/tmp}/voyahtune-apollo.XXXXXX")
chmod 700 "$backup_dir"
echo "Резервные копии на Mac: $backup_dir"

adb shell am force-stop ru.big.town.restoremode
adb shell am force-stop ru.big.town.anative

for f in "$restore_prefs" "$restore_prefs.bak" "$native_prefs" "$native_prefs.bak"; do
    if adb shell "test -f '$f'"; then
        adb exec-out cat "$f" > "$backup_dir/$(basename "$f")"
        test -s "$backup_dir/$(basename "$f")" || {
            echo "Не удалось сохранить $f. Изменения не начаты." >&2
            exit 1
        }
    fi
done

adb shell sh -s <<'DEVICE_SCRIPT'
set -eu
restore_prefs=/data/user/0/ru.big.town.restoremode/shared_prefs/DrivePreferences.xml
native_prefs=/data/user/0/ru.big.town.anative/shared_prefs/NativePrefs.xml
for f in "$restore_prefs" "$restore_prefs.bak" "$native_prefs" "$native_prefs.bak"; do
    [ -f "$f" ] || continue
    tmp="${f}.apollo-edit-tmp"
    sed \
        -e 's/name="apolloStockUiEnabled" value="true"/name="apolloStockUiEnabled" value="false"/g' \
        -e 's/name="apolloTlcEnabled" value="true"/name="apolloTlcEnabled" value="false"/g' \
        -e 's/name="apolloTrafficLightsEnabled" value="true"/name="apolloTrafficLightsEnabled" value="false"/g' \
        -e 's/name="apolloGreenSoundEnabled" value="true"/name="apolloGreenSoundEnabled" value="false"/g' \
        -e 's/name="apolloTrafficSignsEnabled" value="true"/name="apolloTrafficSignsEnabled" value="false"/g' \
        -e 's/name="cacheApolloStockUiEnabled" value="true"/name="cacheApolloStockUiEnabled" value="false"/g' \
        -e 's/name="cacheApolloTlcEnabled" value="true"/name="cacheApolloTlcEnabled" value="false"/g' \
        -e 's/name="cacheApolloTrafficLightsEnabled" value="true"/name="cacheApolloTrafficLightsEnabled" value="false"/g' \
        -e 's/name="cacheApolloGreenSoundEnabled" value="true"/name="cacheApolloGreenSoundEnabled" value="false"/g' \
        -e 's/name="cacheApolloTrafficSignsEnabled" value="true"/name="cacheApolloTrafficSignsEnabled" value="false"/g' \
        "$f" > "$tmp"
    [ -s "$tmp" ] || { rm -f "$tmp"; echo "Пустой результат для $f" >&2; exit 1; }
    cat "$tmp" > "$f" # Keep PackageManager ownership and permissions of the original file.
    rm -f "$tmp"
    if grep -E 'name="(apollo|cacheApollo)[^"]*"[^>]*value="true"' "$f"; then
        echo "Apollo остался включён в $f; перезагрузка отменена." >&2
        exit 1
    fi
done

rm -f /data/user_de/0/ru.big.town.anative/files/apollo_settings_runtime.v1
settings put global open_voyah_apollo_master 0
settings put global open_voyah_apollo_legacy_hook_enabled 0
[ "$(settings get global open_voyah_apollo_master)" = 0 ]
[ "$(settings get global open_voyah_apollo_legacy_hook_enabled)" = 0 ]
[ ! -e /data/user_de/0/ru.big.town.anative/files/apollo_settings_runtime.v1 ]
sync
DEVICE_SCRIPT

echo "Apollo выключен в сохранённых настройках. Перезагрузка ГУ..."
adb reboot
echo "После загрузки проверьте первый переключатель Apollo и загрузку CPU."
