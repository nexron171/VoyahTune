#!/system/bin/sh
set -eu

service=voyahtune_runyn_telnet
state=$(getprop "init.svc.$service")
case "$state" in
    ''|stopped) exit 0 ;;
esac

setprop ctl.stop "$service"
attempt=0
while [ "$attempt" -lt 40 ]; do
    state=$(getprop "init.svc.$service")
    case "$state" in
        ''|stopped) exit 0 ;;
    esac
    attempt=$((attempt + 1))
    sleep 0.25
done

echo 'RunYN telnet service did not stop' >&2
exit 1
