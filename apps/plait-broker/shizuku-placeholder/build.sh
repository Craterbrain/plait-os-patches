#!/bin/sh
# Builds the code-less placeholder APK and stages it into the PlaitOS ROM overlay (Soong re-signs it with the ROM's key).
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; BT=$SDK/build-tools/36.0.0; JAR=$SDK/platforms/android-35/android.jar
[ -f "$JAR" ] || JAR=$(ls $SDK/platforms/*/android.jar | sort -V | tail -1)
rm -rf build && mkdir build
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/placeholder.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip
OUT=${OVERLAY:-$HOME/Projects/plait-os/rom-overlay/vendor/plait/shizukuplaceholder}
mkdir -p "$OUT" && cp build/placeholder.apk "$OUT/PlaitShizukuPlaceholder.apk"
echo "built and staged $OUT/PlaitShizukuPlaceholder.apk"
