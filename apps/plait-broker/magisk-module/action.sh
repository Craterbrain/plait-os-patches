#!/system/bin/sh
# Magisk "Action" button: is the daemon running, as whom, from which APK.
echo "== Plait Broker =="
pid=$(pidof plait_broker)
if [ -z "$pid" ]; then
  echo "daemon: not running"
else
  /system/bin/ps -o user,label,pid,args -p "$pid"
  echo
  echo "running from: $(grep -o -m 1 '/[^ ]*\.apk' "/proc/$pid/maps")"
fi
echo
echo "== Last starts =="
tail -n 5 /data/local/tmp/plait-broker.log 2>/dev/null
