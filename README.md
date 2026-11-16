# PlaitOS patches

🚧 **Under construction.** This repo holds the patch sets and the cloud-build scripts; what else belongs here (docs, licensing notes) is still to be decided.

- One folder per source repo (`frameworks_base` → `frameworks/base`, …), `git format-patch` series for a GrapheneOS tree (tag `2026100600`). Many are ports from crDroid / LineageOS / AICP and keep their original authorship.
- `apply-patches.sh [--check] PATCHES_DIR [TREE]` applies them to a synced tree (skips ones already applied).
- `crave-build.sh` builds **unsigned** target files in the cloud; signing happens on your own computer. No signing key is ever needed or accepted here.

## App sources (`apps/`)

The apps built into the ROM, as staged into `vendor/plait` (the ROM build imports their APKs and signs them with the release key):

| App | Folder |
|---|---|
| Angel Launcher | `apps/angel-launcher` (submodule, [Craterbrain/angel-launcher](https://github.com/Craterbrain/angel-launcher)) |
| Loudness Boost | `apps/loudness-boost` |
| Plait Backup | `apps/plait-backup` (ROM build: `./gradlew assembleRelease -PplaitRom=true`, no root fallback) |
| Plait Broker, Plait Shield, Plait Scan | `apps/plait-broker` (modules `broker`, `shield`, `scanner`) |

Clone with `git clone --recurse-submodules`.
