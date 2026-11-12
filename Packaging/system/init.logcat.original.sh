#!/system/bin/sh

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

  /system/bin/logcat -b all -f ${sdcard_path}/log/logcat.log -r 1024 -n 100 -v usec &
  logcat_pid=$!
  logi "start_logcat: logcat pid = $logcat_pid"
}

kill_logcat ()
{
  logi "kill_logcat: pid = $logcat_pid"
  ## careful not to kill zero or null!
  kill -TERM $logcat_pid
  # this shell doesn't exit now -- wait returns for normal exit
}

start_logcat

#busybox sync
wait $logcat_pid

logi "logcat service stopped"

exit 0
