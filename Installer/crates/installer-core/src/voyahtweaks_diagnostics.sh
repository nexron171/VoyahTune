for path in /data/local/tmp/load.bin /system/etc/init.logcat.sh; do
    printf 'VOYAHTWEAKS_DIAGNOSTIC_BEGIN\t%s\n' "$path"
    if [ ! -e "$path" ]; then
        printf 'VOYAHTWEAKS_DIAGNOSTIC_UNAVAILABLE\tmissing\n'
    elif [ ! -f "$path" ]; then
        printf 'VOYAHTWEAKS_DIAGNOSTIC_UNAVAILABLE\tnot a regular file\n'
    elif ! cat "$path"; then
        printf '\nVOYAHTWEAKS_DIAGNOSTIC_UNAVAILABLE\tread failed\n'
    fi
    printf '\nVOYAHTWEAKS_DIAGNOSTIC_END\t%s\n' "$path"
done
