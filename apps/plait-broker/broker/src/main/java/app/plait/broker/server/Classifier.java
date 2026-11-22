package app.plait.broker.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sorts a command into the broker's tiers (docs/broker-capability-policy.md in PlaitOS):
 * <ul>
 *   <li>{@link Tier#NORMAL}: a device function; the usual per-app command capability decides.</li>
 *   <li>{@link Tier#ASK}: device-wide or privacy-relevant; the user answers a warning dialog once per app and key.</li>
 *   <li>{@link Tier#SCOPED}: acts on one other app. Only allowed for targets the user put on this app's list, one
 *       function and one target per entry; never by mode, never switched on by the requesting app.</li>
 *   <li>{@link Tier#INSTALL}: {@code pm install*}; the installer rules apply.</li>
 *   <li>{@link Tier#GATED}: powerful with no single target (input injection, screen capture, system roles, security
 *       settings, backups, raw Binder calls). Each use while the gate is closed asks the user; an approval opens the
 *       gate for that app and function for a few seconds (10 by default, never more than 60). Never remembered.</li>
 *   <li>{@link Tier#NEVER}: refused in every mode: what would hide what really runs (launchers, interpreters,
 *       {@code app_process}), running code inside another app, and implicit intents sent as the shell.</li>
 * </ul>
 * Acting on the calling app itself is normal. Pure Java: what it needs to know about apps comes through {@link Apps}.
 */
final class Classifier {
    enum Tier { NORMAL, ASK, SCOPED, INSTALL, GATED, NEVER }

    /** Gates for powerful functions without a single target app. */
    enum Gate {
        INPUT("inject taps, keys and other input into any app"),
        SCREEN("capture or read what is on the screen"),
        ROLES("give apps system roles (accessibility, notification access, device admin, default apps, keyboard)"),
        SECURITY("change security settings"),
        BACKUP("back up or restore other apps"),
        BINDER("make raw system calls that bypass the broker's checks"),
        USERS("create or change users and profiles"),
        SYSTEM("change system-wide behavior");

        final String description;

        Gate(String description) {
            this.description = description;
        }

        String id() {
            return name().toLowerCase();
        }
    }

    /** Functions an app may be allowed to use on specific other apps. */
    enum Scope {
        PERMISSIONS("change permissions and app-ops of"),
        FREEZE("freeze, hide, suspend or disable parts of"),
        STOP("force-stop"),
        CLEAR("clear the data of"),
        UNINSTALL("uninstall"),
        FILES("read and change the shared-storage files of"),
        COMPONENTS("start activities and services of, or send broadcasts to"),
        PROVIDERS("read and change the content providers of"),
        THEME("change the look (colors, sizes, switches) of");

        final String description;

        Scope(String description) {
            this.description = description;
        }

        String id() {
            return name().toLowerCase();
        }
    }

    static final class Decision {
        final Tier tier;
        final Scope scope;    // SCOPED
        final Gate gate;      // GATED
        final String target;  // SCOPED: target package
        final String key;     // ASK: what the answer is remembered for; SCOPED: "<scope> <package>"; GATED: gate id
        final String reason;  // ASK, GATED: what it does (dialog text); NEVER: why
        /**
         * NORMAL, but nothing the broker knows to be a plain device function (an unknown {@code cmd} service or Binder
         * interface): a permissive app is asked about it ([key], [reason]) instead of being allowed silently.
         */
        final boolean unlisted;

        private Decision(Tier tier, Scope scope, Gate gate, String target, String key, String reason) {
            this(tier, scope, gate, target, key, reason, false);
        }

        private Decision(Tier tier, Scope scope, Gate gate, String target, String key, String reason, boolean unlisted) {
            this.tier = tier;
            this.scope = scope;
            this.gate = gate;
            this.target = target;
            this.key = key;
            this.reason = reason;
            this.unlisted = unlisted;
        }

        static Decision normal() {
            return new Decision(Tier.NORMAL, null, null, null, null, null);
        }

        static Decision unlisted(String key, String reason) {
            return new Decision(Tier.NORMAL, null, null, null, key, reason, true);
        }

        /** What a permissive app is asked before an unlisted call (see [unlisted]). */
        Decision askInstead() {
            return ask(key, reason);
        }

        static Decision ask(String key, String reason) {
            return new Decision(Tier.ASK, null, null, null, key, reason);
        }

        static Decision scoped(Scope scope, String target) {
            return new Decision(Tier.SCOPED, scope, null, target, scope.id() + " " + target, null);
        }

        static Decision install(String reason) {
            return new Decision(Tier.INSTALL, null, null, null, null, reason);
        }

        static Decision gated(Gate gate, String what) {
            return new Decision(Tier.GATED, null, gate, null, gate.id(), what);
        }

        static Decision never(String reason) {
            return new Decision(Tier.NEVER, null, null, null, null, reason);
        }

        @Override
        public String toString() {
            return tier + (key != null ? " [" + key + "]" : "") + (reason != null ? " " + reason : "");
        }
    }

    /** What the classifier needs to know about installed apps. */
    interface Apps {
        /** The package's recorded installer is [installer]. */
        boolean installedBy(String pkg, String installer);

        /** Both packages are signed with the same certificate. */
        boolean sameSigner(String pkg, String other);

        /** The single package of [uid], or null. */
        String packageOfUid(int uid);

        /** The package that owns the content provider [authority], or null. */
        String providerOwner(String authority);

        /** The user the calling app runs as. */
        default int userId() {
            return 0;
        }
    }

    /** Would hide what really runs, or run code as (or inside) something else. */
    private static final Set<String> NEVER_TOOLS = new HashSet<>(Arrays.asList(
            "run-as", "app_process", "app_process32", "app_process64", "dalvikvm", "dalvikvm64", "linker", "linker64",
            "su", "awk", "xargs", "env", "nice", "ionice", "timeout", "nohup", "setsid", "chroot", "runcon", "strace",
            "logwrapper", "taskset", "chrt", "time", "watch", "flock", "stdbuf", "script", "unshare", "nsenter",
            "busybox", "perl", "python", "python3", "lua", "cmd"));
    private static final Set<String> PM_READ = new HashSet<>(Arrays.asList(
            "list", "path", "dump", "resolve-activity", "query-activities", "query-services", "query-receivers",
            "has-feature", "list-features", "list-libraries", "list-permission-groups", "list-permissions",
            "list-instrumentation", "list-users", "get-install-location", "get-max-users", "get-app-links",
            "check-permission", "get-privapp-permissions", "get-privapp-deny-permissions", "get-oem-permissions"));
    private static final Set<String> PM_FREEZE = new HashSet<>(Arrays.asList(
            "enable", "disable", "disable-user", "disable-until-used", "default-state", "hide", "unhide", "suspend",
            "unsuspend", "install-existing"));
    private static final Set<String> PM_INSTALL = new HashSet<>(Arrays.asList(
            "install", "install-create", "install-write", "install-commit", "install-multiple", "install-abandon",
            "install-add-session", "install-remove"));
    /** pm install options only the shell may use (grant all, downgrade, test-only, skip checks, other users...). */
    private static final Set<String> PM_INSTALL_REFUSED = new HashSet<>(Arrays.asList(
            "-g", "-d", "-t", "--bypass-low-target-sdk-block", "--disable-verification", "--user", "-i",
            "--install-reason", "--apex", "--staged", "--force-queryable", "--restrict-permissions", "--force-sdk",
            "--originating-uri", "--referrer", "--pkg"));
    private static final Set<String> AM_READ = new HashSet<>(Arrays.asList(
            "get-config", "get-current-user", "get-standby-bucket", "package-importance", "get-inactive",
            "get-isolated-pids", "stack", "task", "display"));
    private static final Set<String> AM_STOP = new HashSet<>(Arrays.asList("force-stop", "stop-app", "kill"));
    private static final Set<String> AM_START = new HashSet<>(Arrays.asList(
            "start", "start-activity", "startservice", "start-service", "start-foreground-service", "broadcast"));
    private static final Set<String> DUMPSYS_OK = new HashSet<>(Arrays.asList(
            "battery", "batteryproperties", "thermalservice", "power", "display", "uimode", "cpuinfo"));
    /** Settings keys that take over the phone or weaken its security; never written through the broker. */
    private static final List<String> NEVER_SETTINGS = Arrays.asList(
            "enabled_accessibility_services", "accessibility_enabled", "enabled_notification_listeners",
            "enabled_notification_assistant", "enabled_notification_policy_access_packages", "default_input_method",
            "enabled_input_methods", "selected_input_method_subtype", "adb_enabled", "adb_wifi_enabled",
            "development_settings_enabled", "package_verifier_", "verifier_verify_adb_installs",
            "install_non_market_apps", "lock_screen_", "lockscreen.", "device_provisioned", "user_setup_complete",
            "secure_frp_mode", "always_on_vpn_", "sms_default_application", "dialer_default_application", "assistant",
            "voice_interaction_service", "voice_recognition_service", "autofill_service", "credential_service",
            "http_proxy", "global_http_proxy_", "private_dns_", "bluetooth_name", "mock_location",
            "location_providers_allowed", "notification_listener", "screensaver_components", "default_sms_",
            "managed_provisioning", "secure_frp", "doze_");
    /**
     * An app's own shared-storage directories, under any of the paths that lead there. Case-insensitive: shared
     * storage is (Android/DATA/x is Android/data/x).
     */
    private static final Pattern APP_STORAGE = Pattern.compile(
            "^(?:/sdcard|/storage/emulated/\\d+|/storage/self/primary"
                    + "|/mnt/(?:user|pass_through|installer|androidwritable)/\\d+/emulated/\\d+|/data/media/\\d+)"
                    + "/Android/(?:data|obb|media)/([^/]+)", Pattern.CASE_INSENSITIVE);
    /**
     * The broker's own state, which the shell user (so any command the broker runs) could otherwise rewrite: its policy,
     * the system-write overlay, the DNS blocklist and the su socket. Also the views of other processes' file systems.
     */
    private static final Pattern PROTECTED_PATH = Pattern.compile(
            "^(?:/data/user(?:_de)?/\\d+/com\\.android\\.shell|/data/data/com\\.android\\.shell|/data/plait"
                    + "|/data/local/tmp/plait|/proc/[^/]+/(?:root|cwd|fd|map_files))(?:/.*)?$",
            Pattern.CASE_INSENSITIVE);
    /** {@code cmd} services that are plain device functions; any other service is unlisted (asked in permissive mode). */
    private static final Set<String> DEVICE_CMDS = new HashSet<>(Arrays.asList(
            "wifi", "connectivity", "power", "battery", "display", "uimode", "thermalservice", "deviceidle", "netpolicy",
            "bluetooth_manager", "vibrator_manager", "audio", "window", "batteryproperties"));
    /** Permissions that are not runtime permissions but let an app change or read far more than its own data. */
    private static final Set<String> POWERFUL_PERMISSIONS = new HashSet<>(Arrays.asList(
            "android.permission.WRITE_SECURE_SETTINGS", "android.permission.READ_LOGS", "android.permission.DUMP",
            "android.permission.PACKAGE_USAGE_STATS", "android.permission.WRITE_SETTINGS",
            "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.CHANGE_CONFIGURATION",
            "android.permission.SET_DEBUG_APP", "android.permission.INTERACT_ACROSS_USERS"));
    /** App-ops an app may never loosen for itself through the broker: they guard the screen and Android's own protections. */
    private static final Set<String> SELF_OPS_GATED = new HashSet<>(Arrays.asList(
            "PROJECT_MEDIA", "ACCESS_RESTRICTED_SETTINGS", "SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION"));
    /** App-op modes that give an app more than it has (by name or number). */
    private static final Set<String> LOOSE_MODES = new HashSet<>(Arrays.asList(
            "allow", "allowed", "default", "foreground", "0", "3", "4"));

    /** Options of the pm subcommands that act on one app, and how many values each takes (PackageManagerShellCommand). */
    private static final java.util.Map<String, java.util.Map<String, Integer>> PM_OPTIONS = new java.util.HashMap<>();

    private static void pmOptions(String subs, Object... optionArity) {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        m.put("--user", 1);
        for (int i = 0; i < optionArity.length; i += 2) m.put((String) optionArity[i], (Integer) optionArity[i + 1]);
        for (String s : subs.split(" ")) PM_OPTIONS.put(s, m);
    }

    static {
        pmOptions("grant revoke", "--all-permissions", 0);
        pmOptions("enable disable disable-user disable-until-used default-state hide unhide unsuspend");
        pmOptions("suspend", "--dialogMessage", 1, "--info", 1, "--aed", 2, "--ael", 2, "--aes", 2, "--led", 2,
                "--lel", 2, "--les", 2);
        pmOptions("install-existing", "--ephemeral", 0, "--full", 0, "--instant", 0, "--restrict-permissions", 0,
                "--wait", 0);
        pmOptions("clear", "--cache-only", 0);
        pmOptions("uninstall", "-k", 0, "--versionCode", 1);
    }
    /** pm subcommands that take several packages. */
    private static final Set<String> PM_MANY = new HashSet<>(Arrays.asList("suspend", "unsuspend"));

    private Classifier() {
    }

    static Decision command(List<String> argv, String caller, Apps apps) {
        return command(argv, null, caller, apps);
    }

    /** [cwd] is the directory the command runs in (relative paths are read against it), or null for the daemon's. */
    static Decision command(List<String> argv, String cwd, String caller, Apps apps) {
        String base = cwd != null && cwd.startsWith("/") ? cwd : "/";
        if (cwd != null && !cwd.startsWith("/")) return Decision.never("a relative working directory");
        // The working directory and every argument that can be a path (also "if=PATH", "--file=PATH", "-fPATH")
        List<String> paths = new ArrayList<>();
        paths.add(base);
        for (String a : argv.subList(1, argv.size())) paths.addAll(pathsIn(a, base));
        for (String p : paths) {
            if (isProtected(p)) return Decision.never("the broker's own files and other processes' views are off limits");
        }
        // Another app's files in shared storage: whatever the tool, it acts on that app
        for (String p : paths) {
            String target = storageOwner(p);
            if (target != null && !target.equalsIgnoreCase(caller)) return Decision.scoped(Scope.FILES, target);
        }
        // Other users and profiles (work profile, private space): the shell may act on them, the app may not
        for (int i = 1; i < argv.size(); i++) {
            String a = argv.get(i);
            String user = a.equals("--user") && i + 1 < argv.size() ? argv.get(i + 1)
                    : a.startsWith("--user=") ? a.substring(7) : null;
            if (user != null && !user.equals("current") && !user.equals("cur")
                    && !user.equals(String.valueOf(apps.userId()))) {
                return Decision.never("acting in another user or profile (--user " + user + ")");
            }
        }
        return classify(argv, caller, apps);
    }

    private static Decision classify(List<String> argv, String caller, Apps apps) {
        String tool = argv.get(0).substring(argv.get(0).lastIndexOf('/') + 1);
        if (tool.equals("toybox") && argv.size() > 1) return classify(argv.subList(1, argv.size()), caller, apps);
        if (tool.equals("cmd") && argv.size() > 1) {
            // cmd's only options come before the service: -w (wait for it) changes nothing, -l lists services
            if (argv.get(1).equals("-w")) {
                if (argv.size() < 3) return Decision.never("cmd -w without a service");
                List<String> unwrapped = new ArrayList<>(argv);
                unwrapped.remove(1);
                return classify(unwrapped, caller, apps);
            }
            if (argv.get(1).equals("-l")) return Decision.normal();
            if (argv.get(1).startsWith("-")) return Decision.never("cmd " + argv.get(1));
            List<String> rest = argv.subList(2, argv.size());
            switch (argv.get(1)) {
                case "package": return pm(rest, caller, apps);
                case "appops": return appops(rest, caller, apps);
                case "activity": return am(rest, caller);
                case "settings": return settings(rest);
                case "overlay": return overlay(rest, caller, apps);
                case "role": case "notification": case "accessibility": case "voiceinteraction": case "autofill":
                case "credential": case "telecom":
                    return Decision.gated(Gate.ROLES, "cmd " + argv.get(1));
                case "device_policy": case "lock_settings":
                    return Decision.gated(Gate.SECURITY, "cmd " + argv.get(1));
                case "input": return Decision.gated(Gate.INPUT, "cmd input");
                case "backup": return Decision.gated(Gate.BACKUP, "cmd backup");
                case "user": return Decision.gated(Gate.USERS, "cmd user");
                case "jobscheduler": return Decision.gated(Gate.SYSTEM, "cmd jobscheduler (runs other apps' jobs)");
                case "sensor_privacy": return Decision.gated(Gate.SECURITY, "cmd sensor_privacy (camera and microphone switches)");
                case "companiondevice": return Decision.gated(Gate.ROLES, "cmd companiondevice (companion app access)");
                case "appwidget": return Decision.gated(Gate.ROLES, "cmd appwidget (widget binding for any app)");
                case "media_session": return Decision.gated(Gate.INPUT, "cmd media_session (sends media keys)");
                case "device_config": return Decision.gated(Gate.SYSTEM, "cmd device_config (system feature flags)");
                default:
                    if (DEVICE_CMDS.contains(argv.get(1))) return Decision.normal();
                    return Decision.unlisted("cmd " + argv.get(1), "use the system service \"" + argv.get(1)
                            + "\", which the broker does not know to be a plain device function");
            }
        }
        switch (tool) {
            case "pm": return pm(argv.subList(1, argv.size()), caller, apps);
            case "appops": return appops(argv.subList(1, argv.size()), caller, apps);
            case "am": return am(argv.subList(1, argv.size()), caller);
            case "settings": return settings(argv.subList(1, argv.size()));
            case "logcat": return Decision.ask("logcat", "read the system log, which contains other apps' messages");
            case "input": case "monkey": return Decision.gated(Gate.INPUT, tool);
            case "screencap": case "screenrecord": return Decision.gated(Gate.SCREEN, tool);
            case "uiautomator": return Decision.gated(Gate.SCREEN, "uiautomator (reads and drives the screen)");
            case "dpm": return Decision.gated(Gate.SECURITY, "dpm (device admin and owner)");
            case "ime": case "telecom": return Decision.gated(Gate.ROLES, tool);
            case "bmgr": case "bu": return Decision.gated(Gate.BACKUP, tool);
            case "service": return Decision.gated(Gate.BINDER, "service call");
            case "content": return content(argv.subList(1, argv.size()), caller, apps);
            case "dumpsys": {
                String service = argv.size() > 1 && !argv.get(1).startsWith("-") ? argv.get(1) : null;
                if (service != null && DUMPSYS_OK.contains(service)) return Decision.normal();
                return Decision.ask("dumpsys " + (service != null ? service : "(everything)"),
                        "read system service state, which can include other apps' data");
            }
            case "find":
                for (String a : argv) {
                    if (a.equals("-exec") || a.equals("-execdir") || a.equals("-ok") || a.equals("-okdir") || a.equals("-delete")) {
                        return Decision.never("find -exec/-delete runs or deletes beyond what was approved");
                    }
                }
                return Decision.normal();
            default:
                if (NEVER_TOOLS.contains(tool)) return Decision.never(tool + " is never available through the broker");
                // What the tool or its options do beyond the ordinary: launch, serve, need root, see or stop processes
                Decision d = Tools.classify(argv);
                return d != null ? d : Decision.normal();
        }
    }

    // ---------------------------------------------------------------- pm / cmd package

    private static Decision pm(List<String> args, String caller, Apps apps) {
        if (args.isEmpty()) return Decision.never("pm without a command");
        String sub = args.get(0);
        List<String> ops = operands(args.subList(1, args.size()), "--user");
        if (PM_READ.contains(sub)) return Decision.normal();
        if (PM_INSTALL.contains(sub)) {
            for (String a : args) {
                if (PM_INSTALL_REFUSED.contains(a)) return Decision.never("pm " + sub + " " + a + " is a shell-only install option");
            }
            return Decision.install("pm " + sub);
        }
        if (sub.equals("set-home-activity")) return Decision.gated(Gate.ROLES, "pm set-home-activity");
        if (sub.equals("create-user") || sub.equals("remove-user")) return Decision.gated(Gate.USERS, "pm " + sub);
        Scope scope = sub.equals("grant") || sub.equals("revoke") ? Scope.PERMISSIONS
                : PM_FREEZE.contains(sub) ? Scope.FREEZE
                : sub.equals("clear") ? Scope.CLEAR
                : sub.equals("uninstall") ? Scope.UNINSTALL
                : null;
        if (scope == null) return Decision.gated(Gate.SYSTEM, "pm " + sub);
        // Read the arguments the way pm does: an option's values are never mistaken for the target
        java.util.Map<String, Integer> known = PM_OPTIONS.get(sub);
        if (known == null) return Decision.gated(Gate.SYSTEM, "pm " + sub);
        ops = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            if (a.startsWith("-")) {
                Integer arity = known.get(a);
                if (arity == null) return Decision.never("pm " + sub + " " + a + " is not an option the broker knows");
                i += arity;
            } else {
                ops.add(a);
            }
        }
        if (ops.isEmpty()) return Decision.gated(Gate.SYSTEM, "pm " + sub);
        List<String> targets = new ArrayList<>();
        for (String op : PM_MANY.contains(sub) ? ops : ops.subList(0, 1)) {
            targets.add(op.contains("/") ? op.substring(0, op.indexOf('/')) : op);
        }
        String other = null;
        for (String t : targets) {
            if (!t.equals(caller)) {
                if (other != null && !other.equals(t)) return Decision.never("pm " + sub + " of several other apps at once");
                other = t;
            }
        }
        if (other == null) {
            if (sub.equals("grant")) return selfGrant(ops.size() > 1 ? ops.get(1) : null, args.contains("--all-permissions"));
            return Decision.normal();
        }
        if (scope == Scope.UNINSTALL && apps.installedBy(other, caller)) {
            return Decision.ask("uninstall " + other, "uninstall " + other + ", which it installed");
        }
        return Decision.scoped(scope, other);
    }

    /** An app granting a permission to itself: asked once per permission, with what it really means. */
    static Decision selfGrant(String permission, boolean all) {
        if (all || permission == null) {
            return Decision.ask("grant itself all permissions", "grant itself every permission it requests, without Android's own questions");
        }
        String name = permission.substring(permission.lastIndexOf('.') + 1);
        if (POWERFUL_PERMISSIONS.contains(permission)) {
            return Decision.ask("grant itself " + permission, "grant itself " + name + " for good. This is not a normal "
                    + "app permission: it lets the app change or read far more than its own data, without the broker");
        }
        return Decision.ask("grant itself " + permission, "grant itself " + name + " without Android's own question");
    }

    /** An app setting one of its own app-ops; null if it only takes something away. */
    static Decision selfAppOp(String op, String mode) {
        String name = op == null ? "" : op.toUpperCase(java.util.Locale.ROOT);
        if (name.startsWith("ANDROID:")) name = name.substring(8);
        if (mode != null && !LOOSE_MODES.contains(mode.toLowerCase(java.util.Locale.ROOT))) return null;
        if (SELF_OPS_GATED.contains(name)) {
            return Decision.gated(name.equals("PROJECT_MEDIA") ? Gate.SCREEN : Gate.SECURITY, "allow itself " + name);
        }
        return Decision.ask("app-op itself " + name, "allow itself " + name + " without Android's own question");
    }

    // ---------------------------------------------------------------- appops / cmd appops

    private static Decision appops(List<String> args, String caller, Apps apps) {
        if (args.isEmpty()) return Decision.never("appops without a command");
        String sub = args.get(0);
        if (sub.equals("get") || sub.equals("query-op")) return Decision.normal();
        if (!sub.equals("set") && !sub.equals("reset")) return Decision.gated(Gate.SYSTEM, "appops " + sub);
        for (String a : args.subList(1, args.size())) {
            if (a.startsWith("-") && !a.equals("--user") && !a.equals("--uid")) {
                return Decision.never("appops " + sub + " " + a + " is not an option the broker knows");
            }
        }
        List<String> ops = operands(args.subList(1, args.size()), "--user");
        if (ops.isEmpty()) return Decision.gated(Gate.SYSTEM, "appops " + sub + " for every app");
        String target = ops.get(0);
        if (target.matches("\\d+")) {
            target = apps.packageOfUid(Integer.parseInt(target));
            if (target == null) return Decision.never("appops for a uid that is not one app");
        }
        if (!target.equals(caller)) return Decision.scoped(Scope.PERMISSIONS, target);
        // Its own app-ops: taking something away is normal, giving itself more is asked (or gated)
        if (sub.equals("reset")) return Decision.ask("app-op itself reset", "reset its app-ops, undoing what you denied it");
        Decision d = selfAppOp(ops.size() > 1 ? ops.get(1) : null, ops.size() > 2 ? ops.get(2) : null);
        return d != null ? d : Decision.normal();
    }

    // ---------------------------------------------------------------- am / cmd activity

    private static Decision am(List<String> args, String caller) {
        if (args.isEmpty()) return Decision.never("am without a command");
        String sub = args.get(0);
        if (AM_READ.contains(sub)) return Decision.normal();
        List<String> ops = operands(args.subList(1, args.size()), "--user");
        if (AM_STOP.contains(sub)) {
            for (String a : args.subList(1, args.size())) {
                if (a.startsWith("-") && !a.equals("--user")) return Decision.never("am " + sub + " " + a + " is not an option the broker knows");
            }
            if (ops.isEmpty()) return Decision.never("am " + sub + " of nothing");
            if (ops.size() > 1) return Decision.never("am " + sub + " of more than one app");
            return ops.get(0).equals(caller) ? Decision.normal() : Decision.scoped(Scope.STOP, ops.get(0));
        }
        if (AM_START.contains(sub)) {
            // am keeps the last -n, and an intent URI argument can name its own component: only one, and no URIs
            int n = args.indexOf("-n");
            if (n != args.lastIndexOf("-n")) return Decision.never("am " + sub + " with more than one -n");
            for (String a : args) {
                String l = a.toLowerCase(java.util.Locale.ROOT);
                if (l.startsWith("intent:") || l.startsWith("android-app:") || l.contains("#intent;") || l.contains(";component=")
                        || a.equals("--selector")) {
                    return Decision.never("am " + sub + " with an intent URI or selector (it can name another component)");
                }
            }
            String component = n >= 0 && n + 1 < args.size() ? args.get(n + 1) : null;
            if (component == null || !component.contains("/")) {
                return Decision.never("am " + sub + " without an explicit component would be sent with the shell's identity");
            }
            String target = component.substring(0, component.indexOf('/'));
            return target.equals(caller) ? Decision.normal() : Decision.scoped(Scope.COMPONENTS, target);
        }
        if (sub.equals("instrument") || sub.equals("set-debug-app") || sub.equals("profile") || sub.equals("dumpheap")
                || sub.equals("attach-agent")) {
            return Decision.never("am " + sub + " runs or inspects code inside another app");
        }
        return Decision.gated(Gate.SYSTEM, "am " + sub);
    }

    // ---------------------------------------------------------------- settings / cmd settings

    private static Decision settings(List<String> args) {
        List<String> ops = operands(args, "--user");
        if (ops.isEmpty()) return Decision.never("settings without a command");
        String sub = ops.get(0);
        String namespace = ops.size() > 1 ? ops.get(1) : "";
        String key = ops.size() > 2 ? ops.get(2) : "";
        if (!namespace.equals("global") && !namespace.equals("system") && !namespace.equals("secure")) {
            return Decision.gated(Gate.SYSTEM, "settings " + sub + " " + namespace);
        }
        switch (sub) {
            case "get":
            case "list":
                return namespace.equals("secure")
                        ? Decision.ask("settings " + sub + " secure" + (key.isEmpty() ? "" : " " + key),
                                "read secure settings, which include identifiers like android_id")
                        : Decision.normal();
            case "put":
            case "delete":
                if (key.isEmpty()) return Decision.gated(Gate.SYSTEM, "settings " + sub + " without a key");
                // The DNS provider for every app: asked once per app, under one answer for its mode and hostname
                if (namespace.equals("global") && (key.equals("private_dns_mode") || key.equals("private_dns_specifier"))) {
                    return Decision.ask("private dns", "change the DNS provider every app uses");
                }
                for (String k : NEVER_SETTINGS) {
                    if (k.endsWith("_") || k.endsWith(".") ? key.startsWith(k) : key.equals(k)) {
                        return Decision.gated(Gate.SECURITY, "settings " + sub + " " + namespace + " " + key);
                    }
                }
                return Decision.ask("settings put " + namespace + " " + key, "change the " + namespace + " setting " + key);
            default:
                return Decision.gated(Gate.SYSTEM, "settings " + sub + " " + namespace);
        }
    }

    // ---------------------------------------------------------------- cmd overlay

    private static Decision overlay(List<String> args, String caller, Apps apps) {
        if (args.isEmpty()) return Decision.never("cmd overlay without a command");
        String sub = args.get(0);
        if (sub.equals("list") || sub.equals("lookup") || sub.equals("dump")) return Decision.normal();
        if (sub.equals("enable") || sub.equals("disable") || sub.equals("enable-exclusive")) {
            List<String> ops = operands(args.subList(1, args.size()), "--user", "--category");
            if (ops.isEmpty()) return Decision.never("cmd overlay " + sub + " of nothing");
            String overlay = ops.get(0);
            if (overlay.equals(caller) || apps.sameSigner(overlay, caller)) {
                return Decision.ask("overlay " + sub + " " + overlay, sub + " its own theme overlay " + overlay
                        + ", which changes how other apps look");
            }
            return Decision.gated(Gate.SYSTEM, "cmd overlay " + sub + " " + overlay + " (not the app's own overlay)");
        }
        return Decision.gated(Gate.SYSTEM, "cmd overlay " + sub);
    }

    // ---------------------------------------------------------------- content

    private static Decision content(List<String> args, String caller, Apps apps) {
        int u = args.indexOf("--uri");
        // content keeps the last --uri: only one
        if (u != args.lastIndexOf("--uri")) return Decision.never("content with more than one --uri");
        String uri = u >= 0 && u + 1 < args.size() ? args.get(u + 1) : null;
        if (uri == null || !uri.startsWith("content://")) return Decision.never("content without a content:// uri");
        String authority = uri.substring("content://".length()).split("[/?]", 2)[0];
        if (authority.equals("settings")) return Decision.gated(Gate.SECURITY, "content on the settings provider");
        String owner = apps.providerOwner(authority);
        if (owner == null) return Decision.never("content provider " + authority + " has no single owner");
        return owner.equals(caller) ? Decision.normal() : Decision.scoped(Scope.PROVIDERS, owner);
    }

    // ---------------------------------------------------------------- helpers

    /** The package whose shared-storage directory [path] is in, or null. */
    static String storageOwner(String path) {
        if (!path.startsWith("/")) return null;
        Matcher m = APP_STORAGE.matcher(normalize(path));
        if (m.find()) return m.group(1);
        try {
            m = APP_STORAGE.matcher(new java.io.File(path).getCanonicalPath());
            return m.find() ? m.group(1) : null;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** [path] (absolute) is the broker's own state or a view of another process's files. */
    static boolean isProtected(String path) {
        if (PROTECTED_PATH.matcher(normalize(path)).matches()) return true;
        // On the phone, also where symbolic links lead (a link the app made earlier, /sdcard -> /storage/...)
        try {
            String canonical = new java.io.File(path).getCanonicalPath();
            return PROTECTED_PATH.matcher(canonical).matches();
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The absolute paths an argument may name: itself, the value of {@code key=value} and {@code --opt=value}, and what
     * follows a short option ({@code -f/path}); relative ones are read against [base].
     */
    static List<String> pathsIn(String arg, String base) {
        List<String> candidates = new ArrayList<>();
        candidates.add(arg);
        int eq = arg.indexOf('=');
        if (eq >= 0) candidates.add(arg.substring(eq + 1));
        if (arg.startsWith("-") && arg.indexOf('/') > 0) candidates.add(arg.substring(arg.indexOf('/')));
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            if (c.isEmpty() || c.startsWith("-")) continue;
            out.add(c.startsWith("/") ? c : base.endsWith("/") ? base + c : base + "/" + c);
        }
        return out;
    }

    private static String normalize(String path) {
        try {
            return java.nio.file.Paths.get(path).normalize().toString();
        } catch (RuntimeException e) {
            return path;
        }
    }

    /** Non-option arguments, skipping the values of the given options (e.g. "--user 0"). */
    private static List<String> operands(List<String> args, String... optionsWithValue) {
        Set<String> withValue = new HashSet<>(Arrays.asList(optionsWithValue));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (withValue.contains(a)) {
                i++;
            } else if (!a.startsWith("-")) {
                out.add(a);
            }
        }
        return out;
    }
}
