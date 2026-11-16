#!/bin/sh
# Builds plait-shield-magisk.zip from ./shield-module: the release APK as a /product system app, where PlaitOS puts it
set -e
cd "$(dirname "$0")"
./gradlew -q :shield:assembleRelease
stage=build/shield-module
rm -rf "$stage" plait-shield-magisk.zip
cp -r shield-module "$stage"
mkdir -p "$stage/system/product/app/PlaitShield"
cp shield/build/outputs/apk/release/shield-release.apk "$stage/system/product/app/PlaitShield/PlaitShield.apk"
(cd "$stage" && zip -qr ../../plait-shield-magisk.zip .)
echo built plait-shield-magisk.zip
