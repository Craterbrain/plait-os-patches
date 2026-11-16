#!/bin/sh
# Builds plait-overlay-helper-magisk.zip (a root helper for test phones; PlaitOS does not need it)
set -e
cd "$(dirname "$0")"
rm -f plait-overlay-helper-magisk.zip
(cd overlay-helper-module && zip -qr ../plait-overlay-helper-magisk.zip .)
echo built plait-overlay-helper-magisk.zip
