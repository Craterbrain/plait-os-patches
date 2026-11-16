#!/system/bin/sh
# Magisk late_start service: stands in for PlaitOS's init service (system/etc/init/plait_broker.rc) on a rooted test
# phone. Same launcher, same user, same SELinux context (u:r:shell:s0), so what works here works in the ROM.
# Runs under Magisk's busybox (standalone ash): pidof/date here are busybox applets, su is Magisk's.
LOG=/data/local/tmp/plait-broker.log
touch "$LOG" && chown 2000:2000 "$LOG"

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done

# init restarts a service that exits; this loop does the same (the daemon exits itself when the app is updated)
while true; do
  if ! pidof plait_broker >/dev/null; then
    echo "$(date '+%m-%d %H:%M:%S') starting broker" >> "$LOG"
    # uid shell with the groups from plait_broker.rc: shell(2000) log(1007) readproc(3009) inet(3003).
    # Options must come before the uid: Magisk 31.1's su stops parsing options at the first plain argument
    # (earlier versions reordered them), and -Z is the documented spelling of the old -cn.
    su -g 2000 -G 1007 -G 3009 -G 3003 -Z u:r:shell:s0 2000 \
      -c "setsid /system/bin/sh /system_ext/bin/plait-broker >> $LOG 2>&1 < /dev/null &"
  fi
  sleep 20
done

