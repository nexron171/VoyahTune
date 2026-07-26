#!/system/bin/sh
# Чистая версия init.logcat.sh — ТОЛЬКО инициализация логирования, без наших «допов»
# (без setenforce 0, telnetd и вечного цикла запуска load.bin). Кладётся при удалении
# (remove.sh/bat) в /system/etc/init.logcat.sh, чтобы вернуть систему к состоянию без рут-обвязки.

LOG_TAG="logcat"
LOG_NAME="${0}:"

logcat_pid=""
cp_pid=""

sdcard_path="/logcache"
mount | grep "/logcache type vfat" > /dev/null


loge ()
{
  /system/bin/log -t $LOG_TAG -p e "$LOG_NAME $@"
}

logi ()
{
  /system/bin/log -t $LOG_TAG -p i "$LOG_NAME $@"
}

failed ()
{
  loge "$1: exit code $2"
  exit $2
}

start_logcat ()
{
    if [ ! -d "${sdcard_path}/log" ]; then
        /system/bin/mkdir ${sdcard_path}/log/
    fi

  /system/bin/logcat -b main -b system -b events -b crash -f ${sdcard_path}/log/logcat.log -r 1024 -n 100 -v threadtime &
  logcat_pid=$!
  logi "start_logcat: logcat pid = $logcat_pid"
}

start_logcat

wait $logcat_pid

logi "logcat service stopped"

exit 0
