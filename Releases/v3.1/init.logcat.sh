#!/system/bin/sh
# init.logcat.sh Open Voyah: штатное логирование + setenforce 0 + запуск нашего Frida-оркестратора
# (load.bin: vd_bypass.js в system_server для VirtualDisplay-сплита + keymng2.js для кнопки-звёздочки).
# remove.sh возвращает init.logcat.original.sh (чистое логирование, без рут-обвязки).

LOG_TAG="logcat"
LOG_NAME="${0}:"
logcat_pid=""
sdcard_path="/logcache"
mount | grep "/logcache type vfat" > /dev/null

logi () { /system/bin/log -t $LOG_TAG -p i "$LOG_NAME $@"; }

start_logcat () {
    if [ ! -d "${sdcard_path}/log" ]; then
        /system/bin/mkdir ${sdcard_path}/log/
    fi
    /system/bin/logcat -b main -b system -b events -b crash -f ${sdcard_path}/log/logcat.log -r 1024 -n 100 -v threadtime &
    logcat_pid=$!
    logi "start_logcat: logcat pid = $logcat_pid"
}

start_logcat

# --- Open Voyah root-обвязка ---
setenforce 0
mkdir -p /data/local/bin/
# Frida-оркестратор в фоне (watchdog-цикл внутри load.bin) — НЕ блокируем загрузку.
# load.bin в /data/local/bin (доступно рано при загрузке; /sdcard монтируется позже!).
/system/bin/sh /data/local/bin/load.bin >> /data/local/tmp/voyah_load.txt 2>&1 &

wait $logcat_pid
logi "logcat service stopped"
exit 0
