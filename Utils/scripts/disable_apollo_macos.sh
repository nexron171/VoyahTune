#!/bin/bash
# Emergency Apollo opt-out for VoyahTune on a parked vehicle (Android 11).
# Run on macOS with one connected, authorized ADB device and adbd root access.
set -euo pipefail

adb_bin=${ADB_BIN:-adb}
restore_prefs=/data/user/0/ru.big.town.restoremode/shared_prefs/DrivePreferences.xml
native_prefs=/data/user/0/ru.big.town.anative/shared_prefs/NativePrefs.xml
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
device_script="$script_dir/../../Packaging/installer/common/apollo-safe-device.sh"
[ -s "$device_script" ] || { echo "Не найден $device_script" >&2; exit 1; }

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

adb shell sh -s < "$device_script"

echo "Apollo выключен в сохранённых настройках. Перезагрузка ГУ..."
adb reboot
echo "После загрузки проверьте настройки автомобиля и загрузку CPU. Цели TLC, светофоров и знаков сохранены."
