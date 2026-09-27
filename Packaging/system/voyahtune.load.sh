#!/system/bin/sh
# voyahtune.load.sh — тело Frida-оркестратора, запускается voyahtune_load из voyahtune.load.rc.
# Извлечено из старого init.logcat.sh (монки-патча штатного логирования): здесь остаётся только
# рут-обвязка, logcat своим порядком поднимает штатный /system/etc/init.logcat.sh (не тронут).
LOG_TAG="vt_load_sh"
logi () { /system/bin/log -t $LOG_TAG -p i "$@"; }

# /data/local/bin — доступно рано при загрузке; /sdcard монтируется позже, там load.bin держать нельзя.
# Restore directory access before injection, including after firmware/third-party changes.
# Never chmod recursively: agent configs stay 0644, root worker state stays private.
prepare_data_directories() {
    mkdir -p /data/local/bin /data/local/tmp &&
    chown 0:0 /data/local /data/local/bin &&
    chown 2000:2000 /data/local/tmp &&
    chmod 00751 /data/local &&
    chmod 00755 /data/local/bin &&
    chmod 00771 /data/local/tmp &&
    test x$(stat -c %a:%u:%g /data/local) = x751:0:0 &&
    test x$(stat -c %a:%u:%g /data/local/bin) = x755:0:0 &&
    test x$(stat -c %a:%u:%g /data/local/tmp) = x771:2000:2000
}
if ! prepare_data_directories; then
    logi "cannot prepare /data/local, bin and tmp permissions"
    exit 1
fi

logi "starting load.bin watchdog"
exec /system/bin/sh /data/local/bin/load.bin >> /data/local/tmp/voyahtune_load.txt 2>&1
