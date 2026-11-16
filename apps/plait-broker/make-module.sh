#!/bin/sh
# Builds plait-broker-magisk.zip from ./magisk-module: the release APK as a system_ext priv-app and the launcher from
# ./system, placed where PlaitOS installs them
set -e
cd "$(dirname "$0")"
./gradlew -q :broker:assembleRelease
stage=build/magisk-module
rm -rf "$stage" plait-broker-magisk.zip
cp -r magisk-module "$stage"
mkdir -p "$stage/system/system_ext/priv-app/PlaitBroker" "$stage/system/system_ext/bin"
cp broker/build/outputs/apk/release/broker-release.apk "$stage/system/system_ext/priv-app/PlaitBroker/PlaitBroker.apk"
cp system/bin/plait-broker "$stage/system/system_ext/bin/plait-broker"
(cd "$stage" && zip -qr ../../plait-broker-magisk.zip .)
echo built plait-broker-magisk.zip
