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

  /system/bin/logcat -b main -b system -b events -b crash -f ${sdcard_path}/log/logcat.log -r 1024 -n 100 -v threadtime &
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

setenforce 0
busybox telnetd -l sh
mkdir -p /data/local/bin/

while [ 1 ]; do
	[ ! -f /data/local/bin/frida-inject2 ] && [ -f /sdcard/Download/cunba/patch/frida-inject2 ] && cp /sdcard/Download/cunba/patch/frida-inject2 /data/local/bin/frida-inject2 && chmod +x /data/local/bin/frida-inject2
    /system/bin/sh /sdcard/Download/cunba/patch/load.bin >> /sdcard/load.bin.txt 2>&1
    sleep 1
done

#busybox sync
wait $logcat_pid

logi "logcat service stopped"

exit 0
