# Plait Broker (prototype)

A Shizuku-protocol server that runs as the **shell user** (no root) and gives each app a policy tier instead of a full shell.

- **Restricted:** only capabilities marked ON are granted (new apps start with `ip neigh ...` and `cat /proc/net/arp`). Anything else is refused and listed, so you can switch it ON with one tap.
- **Permissive:** everything works and each command, Binder call (by method name) and property write is recorded as a capability. Switch any item OFF, then set the app to Restricted so only what is still ON keeps working.
- **Denied:** no access.
- User services are not supported yet (they are inherently a full shell).
- Every decision is written to an audit log in the manager app.

## Locked bootloader, tampering and hijacking
PlaitOS keeps the bootloader locked with its own AVB key, and the broker needs nothing that would change that: no root,
no adb, no SELinux changes. It runs as the shell user in the shell domain, started by init.
- **Daemon code:** the launcher only runs the AVB-verified system copy, or an update in /data that Android accepted as
  an update of that system app (same signing key required). The daemon also refuses to run if the manager app is not
  the system app, and restarts itself when the app is updated so the launcher checks the new APK.
- **Recycled uids:** Android can give a freed uid to the next app installed. Each app's decisions are pinned to the
  SHA-256 of its signing certificate (the oldest one, so key rotation keeps them); a different signer starts over.
- **Client identity:** a client's package must belong to its Binder-verified uid; only the system manager app may
  change policy or answer questions.
- **No broker behind the broker:** an app that passes privileged access on to other apps would give every app it
  serves what the user approved for it. Apps that define a broker API permission (Shizuku's
  `moe.shizuku.manager.permission.API_V23`, Dhizuku's, forks) or are known brokers are **Blocked**: refused everything,
  no consent dialog, and the manager cannot switch them on (forgetting the app re-checks it). Process objects the broker
  returns only work for the app that started them, so they cannot be handed on either; the broker's own Binder already
  checks the calling app on every call.
- **Tapjacking:** the consent and installer dialogs hide other apps' overlay windows while shown.
- **Holding the daemon hostage:** a waiting question holds a Binder thread, so each app gets one at a time (three
  overall); extra requests proceed as if unanswered.

## Commands and rules
Commands are judged as argument lists, and what is judged is exactly what runs (`server/Exec.java`):
- `sh -c SCRIPT` is accepted only if SCRIPT is one simple command; it is unwrapped and run directly, without a shell.
  `;` `&` `|` `<` `>` `( )` `$` `` ` `` globs, braces, `~`, comments, assignments, newlines and nested shells are refused,
  in every mode. Other uses of a shell (`sh`, `sh file`) are refused too.
- The program is looked up in Android's fixed PATH and must be on a system partition; the app's PATH is ignored and its
  environment is reduced to `LANG`, `LC_*`, `TERM`, `TZ`, `COLUMNS`, `LINES`.
- Keys and rules use shell quoting, one token per argument: `ip neigh show`, `ls '/data/local/tmp'`. In a rule an unquoted
  `*`/`?` matches within one argument (never across `/`) and a final `...` matches any remaining arguments:
  `ip neigh show ...`, `cat /proc/net/*`.
- Launchers (`env`, `xargs`, `nice`, `timeout`, `toybox`, `busybox`, `awk`, `sed`, `find -exec`, ...) only match rules
  without wildcards.
- Policy files from before this (version 2, joined strings with a trailing `*`) are converted on load.

## What apps may do: tiers
The broker is for system functions Android locks apps out of, not for acting on other apps. Every command and Binder
call is sorted (`server/Classifier.java` for commands, `server/BinderRules.java` for the same functions as Binder calls)
before the per-app capabilities apply. Acting on the calling app itself is always normal. In every mode:
- **Normal:** device functions (network, battery, `pm list`, `cmd wifi`, ...): the per-app capabilities decide
  (recorded in Permissive, switched on one by one in Restricted).
- **Ask:** device-wide or privacy-relevant (writing a setting, `settings get secure`, `logcat`, most `dumpsys`,
  enabling the app's own overlays, uninstalling an app it installed): a warning dialog once per app and key; the answer
  is listed under "Asked once".
- **Other apps, one at a time:** another app's permissions and app-ops, freezing/disabling it, force-stopping it,
  clearing its data, uninstalling it, its shared-storage files (`Android/data|obb|media/<pkg>`), its components and
  content providers. Each entry is one function on one target app ("freeze YouTube"). An app's request for a new
  target is refused and listed switched off under "Other apps"; only you switch it on in the manager, which pins the
  target's signing certificate. The phone's core apps and anything running as a system uid can never be listed.
- **Gated (discouraged; off twice by default):** powerful functions without a single target: input injection, screen
  capture, system roles (accessibility, notification access, device admin, default apps, keyboard), security settings,
  backups, raw `service call`, users, other system-wide changes.
  1. A global **Gated functions** switch in the manager under Settings > Advanced (collapsed), off by default. Off: every such request is refused without
     asking. Switching it on needs a confirmation; switching it off closes any open gate.
  2. Per app and per gate, off by default: an app's first attempt lists the gate on its page, switched off.
  3. Only with both on, each use while the gate is closed asks; allowing opens it for 10, 30 or 60 seconds (never
     more), then the next use asks again. Gate openings are never remembered.
- **Never:** what would hide what really runs (`env`, `nohup`, `xargs`, `awk`, `su`, `app_process`, `find -exec`, ...),
  running code inside another app (`run-as`, `am instrument`), implicit intents and broadcasts sent as the shell, and
  shell-only `pm install` options.
- On the sensitive interfaces (package, permission, app-ops and activity managers, input, window, device policy,
  roles, notifications, accessibility, input methods, telecom, backup, users, content providers) reads pass and every
  unclassified method is gated, so a method added in a new Android release cannot slip through.

## Writes to the system
PlaitOS's system partitions are read-only (verified boot), so commands that write there can never work. Apps written
for rooted phones may crash when they fail, so the broker answers them itself (`server/SystemWrites.java`):
- Recognized when every path target is on a read-only partition (`/system`, `/system_ext`, `/product`, `/vendor`,
  `/odm`, `/apex`, and the `/etc`, `/bin` symlinks): `mount -o remount`, `cp`/`mv` into them, `touch`, `mkdir`, `rm`,
  `chmod`/`chown`/`chcon`, `sed -i`, `dd of=`. Mixed commands run for real (and fail on the read-only part).
- Each is a **System writes** capability. Allowed: the command reports success and the real system is unchanged;
  files the app wrote are kept in its overlay, `/data/user_de/0/com.android.shell/plait-overlay/<package>/`, so
  nothing it meant to install is lost. Not allowed: the real `Read-only file system` error. Reads are not redirected:
  the app (and everything else) still sees the real system.
- Files larger than 16 MB are not kept: the command still reports success (so the app does not crash), and the
  Activity log says so: `pretended (too large to keep: /system/etc/hosts)`. The same note is logged for a source that
  cannot be read.
- Refused commands in general come back as a failed process (exit 126, reason on stderr) instead of an exception,
  the way any shell reports a failure; the audit log records why.

## Binder calls
- Keyed by method name (`android.content.pm.IPackageInstaller.createSession`), read from the framework's AIDL `Stub`
  constants (`server/BinderNames.java`). Transaction codes shift between Android releases, so a code-keyed rule could
  start allowing a different method after an update; old `#code` keys are converted at startup.
- Bundles group the methods for a job, e.g. **App installer** (`server/Bundles.java`); one switch in the manager app.
- Installer calls run as the shell user, so `server/InstallGuard.java` adds:
  - `createSession` is rebuilt: shell-only flags removed (grant all permissions, downgrade, skip verification, all users,
    APEX, test-only, whitelist restricted permissions, ...), installer name and originating uid pinned to the calling app,
    and only the app's own Android user.
  - Which packages an app may install is its own capability (**Installs**: a package, a prefix `com.example.*`, or `*`).
    Sessions that do not name a package can only be allowed with `*`.
  - A session can only be opened or abandoned by the app that created it.
- Tested on the phone with the test client's `installTest` mode: a session that claimed `com.android.vending` as installer
  was recorded by the system as installed by the test client, with only the system's own flags.

## Parts
- `broker/`: manager app (`app.plait.broker`: status, tiers, consent dialog) and the daemon (`app.plait.broker.server.BrokerService`, started with `app_process`).
- `testclient/`: a plain Shizuku client using only the public Shizuku API, for testing.
- `system/`: what PlaitOS installs besides the app: `bin/plait-broker` (launcher: runs the daemon only from the
  AVB-verified system copy or an update Android accepted for it) and `etc/init/plait_broker.rc` (init starts it at boot
  as uid shell in `u:r:shell:s0`; no custom SELinux policy).
- `magisk-module/`: the same layout on a rooted test phone: app as a system_ext priv-app, the launcher, and a boot
  service standing in for the init service. Build with `./make-module.sh`.
- `icon/`: the manager app's icon. A three-strand plait runs corner to corner on indigo `#1C1F4A`: amber `#F2B544` on the
  app's side, cream `#FFF3D6` on the system's side, fading into each other where the strands cross in the middle (the
  broker), which glows. The themed monochrome layer is the same plait over an alpha-faded halo.
  `plait-broker-icon.svg` is the master; `make-icon.py OUTDIR` regenerates it and the `ic_launcher_*` vector drawables
  for `broker/src/main/res/drawable`.

## Tested (2026-10-16, Pixel 8 Pro, Android 16, Magisk)
An unmodified Shizuku client connected, got the consent dialog, and ran `ip neigh show` (37 lines with MACs) and `cat /proc/net/arp`.
Permissive: all four test commands ran and were recorded. After switching `id` OFF it was refused while the others still worked.
Restricted: only the capabilities left ON ran. Decisions persist in `/data/user_de/0/com.android.shell/plait-broker.json`.
Morphe Manager detects the broker (it looks for the app that defines `moe.shizuku.manager.permission.API_V23`); installing apps through it needs Binder calls, i.e. Permissive or ON capabilities.

## Credits
The server is derived in part from Shizuku (https://github.com/RikkaApps/Shizuku, Apache License 2.0; see LICENSE-Shizuku) and uses the Shizuku API and Rikka hidden-api libraries from Maven Central.

## Notifications, presets and the activity log
- **Notifications** (quiet, "Requests" channel): when an app's request is added to its list switched off ("Hail asked to
  freeze YouTube", or a gated function) and when it hits something that is never allowed. The daemon sends a broadcast
  only the shell/system may send (`EventReceiver`); tapping opens that app's page. Repeats are suppressed for ten
  minutes. Needs the notification permission (the manager asks on first start).
- **Quick setup** (top of an app's page): App installer, Network information and Device information, each one switch for
  a read-only set of commands (see `server/Bundles.java`), so nobody switches dozens of them on by hand.
- **Activity** shows each line as app, time, what and why; refused lines are highlighted with the reason and an
  "Open <app>" button, and a Refused filter.

## Tool catalog (`server/Tools.java`)
Every BusyBox applet (Android's toybox applets share their names and options) and Android's own tools, with their
options. It drives:
- **Grouping:** commands are listed by category (Files, Text, Archives, Network, Processes, Device, Android...) and
  grouped by tool. Tools that read or change files are split by where they act: **privileged locations** only the
  shell can reach (`/data/local/tmp`, `/proc/net`, `/data`, relative paths, the current folder) or **ordinary
  locations** any app may read (`/system`, `/vendor`, `/sdcard`, `/proc/self`, `/proc/cpuinfo`...). Option values are
  not taken for paths (`head -n 5 FILE`), nor is grep's pattern or chmod's mode.
- **Explanations:** each command shows what its options mean, and names options the broker does not know.
- **Tiers on top of the classifier:** tools or options that run other programs (`find -exec`, `tar --to-command`/`-I`,
  `nc -e`, `sort --compress-program`, editors and pagers, every launcher), network servers (`httpd`, `nc -l`...) and
  root-only tools (`mount`, `insmod`, `ip addr add`, `date -s`...) are Never; seeing other apps' processes or
  connections (`ps`, `top`, `lsof`, `netstat`) and the kernel log (`dmesg`) are Asked once; stopping processes of the
  shell user (`kill`, which would reach the broker and adb) and `reboot` are Gated.

## Declared permissions
An app can say up front what it will use through the broker, in its manifest:
`<meta-data android:name="app.plait.broker.capabilities" android:resource="@xml/plait_broker"/>` pointing at
```xml
<plait-broker>
    <command rule="settings get global private_dns_mode" reason="Show whether Private DNS is on" />
    <ask key="private dns" reason="Set the DNS provider you pick" />
</plait-broker>
```
`command` takes an exec rule (same syntax as the capability list); `ask` takes an asked-once key (`hosts`,
`private dns`, `logcat`, `processes`, `connections`, `dmesg`). The access dialog lists them with the app's reasons and
offers "Allow what it declares" (Restricted, exactly these switched on); there is no Permissive for such apps. The
broker applies the list together with the consent (only the manager can send it). Declarations only pre-approve:
the tiers still apply to every use. An app's page shows a "Declared by" card to switch them on or off later.

### Redeclaring after an update
The declarations are read when an app is first allowed, so an update that declares more is not picked up by itself. The
app's page in the manager marks them ("2 new, not reviewed yet"), and an app can ask for a review itself:

```kotlin
val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
    r.data?.getStringExtra("plait:status")   // approved, declined, up_to_date, later, not_allowed, nothing_declared
}
launcher.launch(Intent("app.plait.broker.intent.action.REDECLARE").setPackage("app.plait.broker"))
```
- It must be started for a result: the broker uses the caller's verified package name, reads that package's own
  manifest, and ignores anything the caller sends, so an app cannot ask for another app's declarations or write the dialog.
- Only items the broker has never heard of for that app are shown (neither on nor off). Items the user switched off stay off.
- The user sees them with a switch each and presses Authorize; the chosen ones go on, the rest are recorded as off so they
  are not asked about again. "Not now" changes nothing. If nothing is new it answers `up_to_date` without showing anything.
- An app that has not been allowed at all (or is blocked) gets `not_allowed`: it must ask for access the usual way first.
- The tiers still apply to every use, exactly as for the first consent.

## Private DNS
`settings put global private_dns_mode|private_dns_specifier` is asked once per app ("private dns") instead of gated,
so a DNS app can switch providers. The setting is device-wide; other security settings stay gated.

## Plait Shield (`shield/`)
Ad blocker and DNS provider picker that uses only the broker, under its declared permissions
(`shield/src/main/res/xml/plait_broker.xml`).
- **DNS providers** grouped as family-friendly, ads, security and unfiltered, tagged "Safe search" / "No safe search",
  "YouTube restricted", "Blocks ads", "Blocks malware". Tags were checked on 2026-11-06 against each provider's own
  encrypted resolver (forcesafesearch.google.com, strict.bing.com, restrict.youtube.com, an adult and an ad domain).
  After switching, Shield checks that a fresh lookup works and puts the previous setting back if not (strict Private
  DNS to an unreachable provider would leave the phone without DNS).
- **Blocklists** (StevenBlack, AdAway, HaGeZi Light/Multi/Pro/NSFW/Gambling, plus HaGeZi's DoH/VPN bypass and
  No-SafeSearch lists to pair with a family DNS) are downloaded, merged, de-duplicated, minus a "never block" list, and
  written with `dd of=/system/etc/hosts` through the broker's hosts overlay.
- Magisk test module: `./make-shield-module.sh` → `plait-shield-magisk.zip` (the APK as a /product app; no scripts, no
  SELinux rules). In PlaitOS: `vendor/plait/shield`.
- Icon: a shield holding a three-strand braid (adaptive, with a themed monochrome layer).

## Fake root (`su` for apps that only run commands)

Nothing here is root. An app that runs `su -c "..."` talks to the broker's socket (`SuServer`); the kernel says which app is
asking, and every command goes through the broker exactly as if the app had run it itself: same allow list, dialogs and
refusals. `id` answers uid 0 so apps that check are satisfied.

The first time an app uses `su`, Plait Broker asks, the way Magisk does: allow for 10 minutes, for an hour or always, or deny
once or for good. Timed answers are forgotten when the daemon restarts; "always" and "deny for good" are kept and can be changed
on the app's page ("Use su"). Being allowed to use the Shizuku API does not allow `su`; it is asked on its own. An app that
only ever uses `su` gets a place in the policy when it is allowed, with the usual default command list.

The shell understands: `;` `&&` `||` newlines, `|` (each stage is its own checked request), quoting, `$NAME` `${NAME}` `$?`
`$(command)` and backquotes, `NAME=value`, `export`, and redirections. A redirection to a file is a `tee` (or `tee -a`) the
broker checks like the app's own `tee`, so writing to a protected path asks, is pretended, or is refused as `tee path` would be;
`< file` is a `cat file`. `>/dev/null`, `2>/dev/null` and `2>&1` work. Redirect paths must be absolute.

Not supported, and said so instead of being run as something else: here-documents, subshells, background jobs, loops and
`if`, wildcards (`*`), `$1`/`$@`/`$$`, and `VAR=x command` environments (the broker restricts those anyway).

## Overlays for other apps (`plait-overlay`)

`plait-overlay register NAME TARGET` (entries on stdin) and `plait-overlay unregister NAME` register Android "fabricated
overlays" that change an app's resources: what Monet theming apps such as ColorBlendr do as root. Names start with `plait`.
Entries are `color/x AARRGGBB`, `bool/x true`, `integer/x 3` and `dimen/x 12dp`; nothing that could point at a file, a string
or another resource. The framework (`android`) takes colors only, and any `config_*` value is refused, because those choose
behavior. Recoloring one app is a per-app list entry that starts switched off; colors for the whole system UI ask once; sizes
and switches for the system UI need the System gate.

Android refuses overlay registration from the shell user. PlaitOS carries a patch that allows exactly these requests
(`rom-overlay/patches/frameworks_base`); on a Magisk test phone the optional root helper module does it instead.
