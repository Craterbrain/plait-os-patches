#!/usr/bin/env bash
# Builds the APK and packs it into a Magisk module zip that installs it as a persistent system app.
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" ./gradlew -q assembleRelease
stage=$(mktemp -d)
cp -r magisk/. "$stage/"
mkdir -p "$stage/system/priv-app/LoudnessBoost"
cp app/build/outputs/apk/release/app-release.apk "$stage/system/priv-app/LoudnessBoost/LoudnessBoost.apk"
rm -f loudness-boost-magisk.zip
(cd "$stage" && zip -qr9 "$OLDPWD/loudness-boost-magisk.zip" .)
rm -rf "$stage"
echo "built $(pwd)/loudness-boost-magisk.zip"
