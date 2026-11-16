# PlaitOS patches

🚧 **Under construction.** This repo holds the patch sets and the cloud-build scripts; what else belongs here (docs, licensing notes) is still to be decided.

- One folder per source repo (`frameworks_base` → `frameworks/base`, …), `git format-patch` series for a GrapheneOS tree (tag `2026100600`). Many are ports from crDroid / LineageOS / AICP and keep their original authorship.
- `apply-patches.sh [--check] PATCHES_DIR [TREE]` applies them to a synced tree (skips ones already applied).
- `crave-build.sh` builds **unsigned** target files in the cloud; signing happens on your own computer. No signing key is ever needed or accepted here.
