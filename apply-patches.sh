#!/usr/bin/env bash
# Applies the PlaitOS patch sets (github.com/Craterbrain/plait-os-patches) to a synced GrapheneOS tree.
#   apply-patches.sh [--check] PATCHES_DIR [TREE]      TREE defaults to the current directory
# Each patch is a `git format-patch` mail, applied as a commit with `git am --3way`. A patch whose subject is already
# a commit in the repo is skipped, so running it again is harmless. A patch that does not apply stops the script
# with the repo left in its `git am` state (fix it or `git am --abort`).
set -euo pipefail
CHECK=0; [ "${1:-}" != --check ] || { CHECK=1; shift; }   # --check: only list what would be applied, change nothing
PATCHES="$(cd "${1:?usage: apply-patches.sh PATCHES_DIR [TREE]}" && pwd)"
TREE="$(cd "${2:-.}" && pwd)"

# patch folder -> repo path in the tree
declare -A MAP=(
  [build_make]=build/make
  [build_soong]=build/soong
  [external_zstd]=external/zstd
  [frameworks_base]=frameworks/base
  [frameworks_libs_systemui]=frameworks/libs/systemui
  [frameworks_native]=frameworks/native
  [packages_apps_Launcher3]=packages/apps/Launcher3
  [packages_apps_Settings]=packages/apps/Settings
  [packages_apps_ThemePicker]=packages/apps/ThemePicker
)

[ "$CHECK" = 1 ] || git config --global user.email >/dev/null 2>&1 || [ "$CHECK" = 1 ] || git config --global user.email "plaitos-build@localhost"
[ "$CHECK" = 1 ] || git config --global user.name  >/dev/null 2>&1 || [ "$CHECK" = 1 ] || git config --global user.name "PlaitOS build"

applied=0 skipped=0
for dir in "${!MAP[@]}"; do
  [ -d "$PATCHES/$dir" ] || continue
  repo="$TREE/${MAP[$dir]}"
  [ -d "$repo/.git" ] || [ -f "$repo/.git" ] || { echo "ERROR: ${MAP[$dir]} is not a repo in $TREE" >&2; exit 1; }
  have="$(git -C "$repo" log -n 400 --format=%s)"
  for p in "$PATCHES/$dir"/*.patch; do
    if ! grep -q '^Subject:' "$p"; then
      # a plain diff, not a mail: no commit to match, so it counts as present when it already applies in reverse
      if git -C "$repo" apply --check --reverse "$p" 2>/dev/null; then skipped=$((skipped+1)); continue; fi
      if [ "$CHECK" = 1 ]; then echo "would apply: ${MAP[$dir]}: $(basename "$p")"; applied=$((applied+1)); continue; fi
      git -C "$repo" apply --3way "$p" || { echo "ERROR: $(basename "$p") does not apply to ${MAP[$dir]}" >&2; exit 1; }
      applied=$((applied+1)); continue
    fi
    # the Subject header may be folded over several lines
    subject="$(awk '/^Subject:/{s=$0;f=1;next} f&&/^[ \t]/{s=s $0;next} f{exit} END{print s}' "$p" \
      | sed -E 's/^Subject: *(\[PATCH[^]]*\] *)?//; s/[ \t]+/ /g')"
    # (grep -q on a pipe would make `git log` fail with SIGPIPE under pipefail, so the log is read first)
    if grep -qxF -- "$subject" <<<"$have"; then
      skipped=$((skipped+1)); continue
    fi
    if [ "$CHECK" = 1 ]; then echo "would apply: ${MAP[$dir]}: $subject"; applied=$((applied+1)); continue; fi
    if ! git -C "$repo" am --3way --quiet "$p"; then
      echo "ERROR: $(basename "$p") does not apply to ${MAP[$dir]}" >&2; exit 1
    fi
    applied=$((applied+1))
  done
  echo "${MAP[$dir]}: ok"
done
echo "patches applied: $applied, already present: $skipped"
