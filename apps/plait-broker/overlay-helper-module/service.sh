#!/system/bin/sh
# Magisk late_start service (root). Runs the helper in the broker app (app.plait.broker.server.Overlays): it watches
# /data/plait/ovl/req, re-checks each request (colors only, names starting with "plait") and registers it with the
# overlay manager. Plait Broker's daemon (uid shell) writes the requests; it does the asking and the policy.
LOG=/data/local/tmp/plait-overlay-helper.log
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 3; done
mkdir -p /data/plait/ovl

while true; do
  if ! pidof plait_overlay >/dev/null; then
    APK=/system_ext/priv-app/PlaitBroker/PlaitBroker.apk
    line=$(pm list packages -s -f app.plait.broker 2>/dev/null | grep '=app.plait.broker$' | head -n 1)
    path=${line#package:}; path=${path%=app.plait.broker}
    case "$path" in /data/app/*/base.apk) [ -f "$path" ] && APK=$path ;; esac
    echo "$(date '+%m-%d %H:%M:%S') starting helper from $APK" >> "$LOG"
    app_process -Djava.class.path="$APK" /system/bin --nice-name=plait_overlay \
      app.plait.broker.server.Overlays >> "$LOG" 2>&1 < /dev/null &
  fi
  sleep 20
done
