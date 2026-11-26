#!/usr/bin/env bash
# Builds UNSIGNED PlaitOS target files in the cloud (Crave) and nothing else. The release keys never leave your
# computer: download the two zips this prints, then sign them locally with
#   scripts/build-rom.sh --from-target-files husky-target_files.zip --otatools husky-otatools.zip --target-files-sha256 HASH --otatools-sha256 HASH
#
# Run it from the root of a synced GrapheneOS tree (tag 2026100600, the one the patches were made on):
#   crave run --no-patch -- "bash <(curl -fsSL https://raw.githubusercontent.com/Craterbrain/plait-os-patches/main/crave-build.sh)"
#
# Needs: PLAIT_VENDOR  where vendor/plait comes from: a git URL (the PlaitOS overlay holds prebuilt APKs and is not
#                      in the public patches repo) or a directory of the tree on this machine.
# Optional: PATCHES_URL (default the public patches repo), PATCHES_REF (default main), PROFILE=release|fast.
# Nothing here reads, needs or accepts a signing key.
set -euo pipefail
unset OFFICIAL_BUILD   # never build GrapheneOS's Updater (releases.grapheneos.org)
PATCHES_URL="${PATCHES_URL:-https://github.com/Craterbrain/plait-os-patches.git}"
PATCHES_REF="${PATCHES_REF:-main}"
PROFILE="${PROFILE:-release}"
: "${PLAIT_VENDOR:?set PLAIT_VENDOR to the git URL (or directory) of the vendor/plait overlay}"
[ -d build/soong ] && [ -d vendor/adevtool ] || { echo "run this from the root of a synced GrapheneOS tree"; exit 1; }
ROOT="$PWD"
WORK="$(mktemp -d)"

echo "== patches =="
git clone --depth 1 --branch "$PATCHES_REF" "$PATCHES_URL" "$WORK/patches"
bash "$WORK/patches/apply-patches.sh" "$WORK/patches" "$ROOT"

echo "== vendor/plait =="
rm -rf vendor/plait
if [ -d "$PLAIT_VENDOR" ]; then cp -a "$PLAIT_VENDOR" vendor/plait; else git clone --depth 1 "$PLAIT_VENDOR" vendor/plait; fi
rm -rf vendor/plait/.git

echo "== Google vendor blobs (adevtool) =="
if [ ! -d vendor/google_devices/husky ]; then
  yarn --cwd vendor/adevtool install --frozen-lockfile
  source build/envsetup.sh >/dev/null
  yarn --cwd vendor/adevtool run adevtool generate-all -d husky
fi

echo "== build ($PROFILE) =="
case "$PROFILE" in
  fast) export DISABLE_LTO=true ENABLE_CFI=false ;;
  release) export USE_THINLTO_CACHE=true ;;
  *) echo "unknown PROFILE $PROFILE"; exit 2 ;;
esac
source build/envsetup.sh >/dev/null
lunch plait_husky-cur-user
m vendorbootimage vendorkernelbootimage target-files-package otatools-package

OUT="$ROOT/out/target/product/husky"
TF="$OUT/obj/PACKAGING/target_files_intermediates/plait_husky-target_files.zip"
OT="$ROOT/out/host/linux-x86/obj/ETC/otatools-packagelinux_glibc_x86_64_intermediates/otatools-packagelinux_glibc_x86_64"
mkdir -p "$ROOT/plait-out"
cp "$TF" "$ROOT/plait-out/husky-target_files.zip"
cp "$OT" "$ROOT/plait-out/husky-otatools.zip"
( cd "$ROOT/plait-out" && sha256sum ./*.zip | tee SHA256SUMS )
echo "Download plait-out/*.zip and sign them locally (scripts/build-rom.sh --from-target-files ...). Use both digests from the authenticated build record."
