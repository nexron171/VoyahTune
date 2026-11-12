set -eu

native_package=ru.big.town.anative
native_system_apk=${VOYAHTUNE_SYSTEM_NATIVE_APK:-/system/priv-app/Native/Native.apk}
native_active_path() {
    native_paths=$(pm path --user 0 "$native_package" 2>/dev/null || true)
    case "$native_paths" in
        '') return 0 ;;
        *'
'*) echo 'Native has multiple active APK paths' >&2; exit 1 ;;
        package:*) printf '%s\n' "${native_paths#package:}" ;;
        *) echo 'Cannot determine active Native APK' >&2; exit 1 ;;
    esac
}

native_active=$(native_active_path)
case "$native_active" in
    ''|"$native_system_apk") ;;
    /data/app/*)
        backup_native_data
        native_uninstall=$(pm uninstall -k "$native_package") || {
            printf '%s\n' "$native_uninstall" >&2
            exit 1
        }
        printf '%s\n' "$native_uninstall" | grep -qx Success || exit 1
        native_active=$(native_active_path)
        case "$native_active" in
            ''|"$native_system_apk") ;;
            *) echo 'Native update still overrides the system APK' >&2; exit 1 ;;
        esac ;;
    *) echo "Unexpected active Native APK: $native_active" >&2; exit 1 ;;
esac
