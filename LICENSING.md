# Licensing

Each part of this repository is under the licence of what it comes from. The rule:

- **A patch follows the file it changes.** Patches to AOSP (and the ports from LineageOS, crDroid, AICP and others, which are
  AOSP-licensed) are Apache-2.0; patches to GrapheneOS's own projects are MIT, like those projects.
- **An app based on another app uses that app's licence.** Plait Broker is based on Shizuku, which is Apache-2.0.
- **Everything else is Apache-2.0**, because it is the licence of Android itself, so these apps and patches can be built into
  the ROM and combined with its MIT, BSD and Apache parts without conflict, and it gives users a patent grant.
- **Third-party data and boilerplate keep their own licence**, listed below.

`REUSE.toml` states this per path in the machine-readable [REUSE](https://reuse.software) format; the licence texts are in `LICENSES/`.

| What | Licence | Why |
|---|---|---|
| Patches in `art`, `frameworks_base`, `frameworks_native`, `frameworks_libs_systemui`, `build_make`, `build_soong`, `external_zstd`, `packages_apps_Contacts`, `packages_apps_Dialer`, `packages_apps_Launcher3`, `packages_apps_Settings`, `packages_apps_ThemePicker`, `packages_apps_Traceur`, and `apps/plait-messaging` (a patch series on GrapheneOS's fork of AOSP Messaging, tag `18`) | Apache-2.0 | They change AOSP files. Each patch keeps its author in its `From:` line (ports keep the original author); files a patch adds carry their own header. `external_zstd` only changes AOSP's `Android.bp`, not zstd (BSD-3-Clause OR GPL-2.0). |
| Patches in `branding`, `packages_apps_SetupWizard2`, `packages_apps_Updater` | MIT | They change GrapheneOS's own projects (the `LICENSE` file in each folder is that project's). |
| `apply-patches.sh`, `crave-build.sh`, this documentation | Apache-2.0 | PlaitOS's own work. |
| `apps/plait-backup`, `apps/loudness-boost`, `apps/plait-design`, Plait Shield and Plait Scan (in `apps/plait-broker`) | Apache-2.0 | PlaitOS's own apps and library. |
| `apps/plait-broker/broker` (Plait Broker) | Apache-2.0 | Based on [Shizuku](https://github.com/RikkaApps/Shizuku) (Apache-2.0, RikkaApps); `rikka/shizuku/**` comes from it. `LICENSE-Shizuku` is Shizuku's licence, unchanged. |
| `apps/plait-broker/scanner/src/main/assets/bt_*.tsv` | BSD-3-Clause | The [Bluetooth Numbers Database](https://github.com/NordicSemiconductor/bluetooth-numbers-database) (Nordic Semiconductor); its licence file is beside them. |
| `apps/plait-broker/scanner/src/main/assets/oui.tsv` | GPL-2.0-or-later | Built from [hwdata](https://github.com/vcrhonek/hwdata)'s `oui.txt` (IEEE's public OUI registry), which hwdata offers under GPL-2.0-or-later or the XFree86 licence; the GPL option is taken. It is a data file the app reads, not code it links to. |
| `apps/**/META-INF/com/google/android/*` | GPL-3.0-or-later | The Magisk module installer script, which comes from Magisk. It is a standalone script inside the module zip. |
| Icons `ps_*.xml` and `icons.json` in `apps/plait-design` | Apache-2.0 | Google's [Material Symbols](https://fonts.google.com/icons), converted to Android vector drawables by `tools/icons.py`. |
| Gradle wrapper files | Apache-2.0 | Gradle. |
| `apps/angel-launcher` | GPL-3.0 | A separate project (submodule, [Craterbrain/angel-launcher](https://github.com/Craterbrain/angel-launcher)) with its own licence. The ROM ships it as a separate app. |

Apache-2.0, MIT and BSD-3-Clause code can be combined freely. GPL code here is kept to separate files (a data file, installer scripts) and to
the separate Angel Launcher project, and is not linked into the Apache-licensed apps.

If something here is not marked correctly, or you want one of these under a different licence, open an issue.
