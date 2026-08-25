#!/system/bin/sh
# voyahtune.load.sh — тело Frida-оркестратора, запускается voyahtune_load из voyahtune.load.rc.
# Извлечено из старого init.logcat.sh (монки-патча штатного логирования): здесь остаётся только
# рут-обвязка, logcat своим порядком поднимает штатный /system/etc/init.logcat.sh (не тронут).
LOG_TAG="vt_load_sh"
logi () { /system/bin/log -t $LOG_TAG -p i "$@"; }

# /data/local/bin — доступно рано при загрузке; /sdcard монтируется позже, там load.bin держать нельзя.
mkdir -p /data/local/bin/

logi "starting load.bin watchdog"
exec /system/bin/sh /data/local/bin/load.bin >> /data/local/tmp/voyahtune_load.txt 2>&1
