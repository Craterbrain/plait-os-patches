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

        private Decision(Tier tier, Scope scope, Gate gate, String target, String key, String reason) {
            this.tier = tier;
            this.scope = scope;
            this.gate = gate;
            this.target = target;
            this.key = key;
            this.reason = reason;
        }

        static Decision normal() {
            return new Decision(Tier.NORMAL, null, null, null, null, null);
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
    /** An app's own shared-storage directories, under any of the paths that lead there. */
    private static final Pattern APP_STORAGE = Pattern.compile(
            "^(?:/sdcard|/storage/emulated/\\d+|/storage/self/primary|/mnt/user/\\d+/emulated/\\d+|/data/media/\\d+)"
                    + "/Android/(?:data|obb|media)/([^/]+)");

    private Classifier() {
    }

    static Decision command(List<String> argv, String caller, Apps apps) {
        // Another app's files in shared storage: whatever the tool, it acts on that app
        for (String a : argv.subList(1, argv.size())) {
            String target = storageOwner(a);
            if (target != null && !target.equals(caller)) return Decision.scoped(Scope.FILES, target);
        }
        String tool = argv.get(0).substring(argv.get(0).lastIndexOf('/') + 1);
        if (tool.equals("toybox") && argv.size() > 1) return command(argv.subList(1, argv.size()), caller, apps);
        if (tool.equals("cmd") && argv.size() > 1) {
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
                default: return Decision.normal(); // device services: wifi, connectivity, power, statusbar, ...
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
        if (ops.isEmpty()) return Decision.gated(Gate.SYSTEM, "pm " + sub);
        String target = ops.get(0).contains("/") ? ops.get(0).substring(0, ops.get(0).indexOf('/')) : ops.get(0);
        Scope scope = sub.equals("grant") || sub.equals("revoke") ? Scope.PERMISSIONS
                : PM_FREEZE.contains(sub) ? Scope.FREEZE
                : sub.equals("clear") ? Scope.CLEAR
                : sub.equals("uninstall") ? Scope.UNINSTALL
                : null;
        if (scope == null) return Decision.gated(Gate.SYSTEM, "pm " + sub);
        if (target.equals(caller)) return Decision.normal();
        if (scope == Scope.UNINSTALL && apps.installedBy(target, caller)) {
            return Decision.ask("uninstall " + target, "uninstall " + target + ", which it installed");
        }
        return Decision.scoped(scope, target);
    }

    // ---------------------------------------------------------------- appops / cmd appops

    private static Decision appops(List<String> args, String caller, Apps apps) {
        if (args.isEmpty()) return Decision.never("appops without a command");
        String sub = args.get(0);
        if (sub.equals("get") || sub.equals("query-op")) return Decision.normal();
        if (!sub.equals("set") && !sub.equals("reset")) return Decision.gated(Gate.SYSTEM, "appops " + sub);
        boolean byUid = args.contains("--uid");
        List<String> ops = operands(args.subList(1, args.size()), "--user");
        if (ops.isEmpty()) return Decision.gated(Gate.SYSTEM, "appops " + sub + " for every app");
        String target = ops.get(0);
        if (target.matches("\\d+")) {
            target = apps.packageOfUid(Integer.parseInt(target));
            if (target == null) return Decision.never("appops for a uid that is not one app");
        } else if (byUid && ops.get(0).matches("\\d+")) {
            return Decision.never("appops for a uid");
        }
        return target.equals(caller) ? Decision.normal() : Decision.scoped(Scope.PERMISSIONS, target);
    }

    // ---------------------------------------------------------------- am / cmd activity

    private static Decision am(List<String> args, String caller) {
        if (args.isEmpty()) return Decision.never("am without a command");
        String sub = args.get(0);
        if (AM_READ.contains(sub)) return Decision.normal();
        List<String> ops = operands(args.subList(1, args.size()), "--user");
        if (AM_STOP.contains(sub)) {
            if (ops.isEmpty()) return Decision.never("am " + sub + " of nothing");
            return ops.get(0).equals(caller) ? Decision.normal() : Decision.scoped(Scope.STOP, ops.get(0));
        }
        if (AM_START.contains(sub)) {
            int n = args.indexOf("-n");
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
        String normalized = java.nio.file.Paths.get(path).normalize().toString();
        Matcher m = APP_STORAGE.matcher(normalized);
        return m.find() ? m.group(1) : null;
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
