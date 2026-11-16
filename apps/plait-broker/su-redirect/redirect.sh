#!/system/bin/sh
# TEST PHONES WITH MAGISK ONLY. Run as root:  su -c 'sh redirect.sh com.drdisagree.colorblendr'
# Makes ONE app's "su" be Plait's fake root instead of Magisk's. Done by bind-mounting the fake su inside that app's own
# mount namespace (private to the app), so Magisk's su stays as it is for every other app and for the system.
# The app must be (re)started by this script; its mount namespace is changed right after it appears.
# Undo: force-stop the app, or reboot. Nothing on disk outside /data/adb/plait changes.
PKG=${1:?package name}
D=/data/adb/plait
BB=/data/adb/magisk/busybox

line=$(pm list packages -f app.plait.broker | head -n 1)
APK=${line#package:}; APK=${APK%=app.plait.broker}
[ -f "$APK" ] || { echo "broker APK not found"; exit 1; }

mkdir -p $D
cat > $D/fakesu <<EOS
#!/system/bin/sh
exec /system/bin/app_process -Djava.class.path=$APK /system/bin app.plait.broker.server.SuClient "\$@"
EOS
chmod 0755 $D/fakesu
# apps may execute system_file; they may not execute files labeled for the shell or for adb
chcon u:object_r:system_file:s0 $D/fakesu

am force-stop "$PKG"
monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
pid=
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  pid=$(pidof "$PKG" | cut -d' ' -f1)
  [ -n "$pid" ] && break
  sleep 0.25
done
[ -n "$pid" ] || { echo "$PKG did not start"; exit 1; }

for p in /system/bin/su /system/xbin/su /sbin/su /product/bin/su /system_ext/bin/su /vendor/bin/su; do
  [ -e "$p" ] || continue
  $BB nsenter -t "$pid" -m -- mount --bind $D/fakesu "$p" && echo "su at $p is now Plait's fake root for $PKG (pid $pid)"
done
# apps that look in PATH first when nothing above exists
[ -e /system/bin/su ] || $BB nsenter -t "$pid" -m -- sh -c "touch /system/bin/su 2>/dev/null && mount --bind $D/fakesu /system/bin/su" 2>/dev/null
