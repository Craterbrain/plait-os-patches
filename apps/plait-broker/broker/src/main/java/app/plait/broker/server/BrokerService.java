package app.plait.broker.server;

import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_UID;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IShizukuServiceConnection;
import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.PermissionManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.api.RemoteProcessHolder;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.OsUtils;
import rikka.shizuku.server.util.UserHandleCompat;

/**
 * A Shizuku-protocol server (Binder interface {@code moe.shizuku.server.IShizukuService}) that runs as the shell user
 * and enforces a per-app policy instead of handing every approved app a full shell.
 *
 * <p>Derived in part from Shizuku's server (Apache License 2.0, RikkaApps). What differs:
 * <ul>
 *   <li>SCOPED apps may only run allow-listed commands ({@link Policy#execAllowed});</li>
 *   <li>binder calls through ShizukuBinderWrapper and user services need the explicit FULL tier;</li>
 *   <li>every decision lands in an audit log shown in the manager app.</li>
 * </ul>
 */
public class BrokerService extends IShizukuService.Stub {

    public static final String MANAGER_PACKAGE = "app.plait.broker";
    public static final String REQUEST_PERMISSION_ACTION = MANAGER_PACKAGE + ".intent.action.REQUEST_PERMISSION";
    public static final String EXTRA_MODE = "plait:mode";
    /** "type\nkey" for each capability the app declared (Declared.kt in the manager). */
    public static final String EXTRA_DECLARED = "plait:declared";
    public static final String ASK_ACTION = MANAGER_PACKAGE + ".intent.action.ASK";
    /** A gate opened by the user closes after this long at most, whatever was answered. */
    private static final int MAX_GATE_SECONDS = 60;
    private static final int ANSWER_TIMEOUT_SECONDS = 60;

    // Manager-only transactions (the manager app talks to the daemon over the same Binder).
    public static final int TX_DUMP = 10010;
    public static final int TX_SET_MODE = 10011;
    public static final int TX_SET_CAP = 10012;
    public static final int TX_REMOVE = 10013;
    public static final int TX_ANSWER = 10014; // answers a question the daemon asked through a dialog
    public static final int TX_SET_GATES = 10015; // master switch for gated functions
    public static final int TX_RESET_HOSTS = 10016; // puts the stock hosts file back

    private static final Logger LOGGER = new Logger("BrokerService");

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        BrokerService service = new BrokerService();
        HostsFile.ensure();
        new SuServer(service).start();
        exitWhenAppUpdated();
        Looper.loop();
    }

    /**
     * When the broker app is updated, Android installs it at a new path: exit so init (or the Magisk module's loop)
     * starts the launcher again, which checks the new APK before running it.
     */
    private static void exitWhenAppUpdated() {
        String running = System.getProperty("java.class.path", "");
        Thread t = new Thread(() -> {
            while (true) {
                SystemClock.sleep(30_000);
                ApplicationInfo ai = PackageManagerApis.getApplicationInfoNoThrow(MANAGER_PACKAGE, 0, 0);
                if (ai == null || !running.equals(ai.sourceDir)) {
                    LOGGER.i("broker app changed (%s -> %s); exiting to restart", running, ai != null ? ai.sourceDir : null);
                    System.exit(0);
                }
            }
        }, "app-update-watch");
        t.setDaemon(true);
        t.start();
    }

    private static class Client {
        final int uid, pid;
        final IShizukuApplication app;
        final String packageName;
        final int apiVersion;
        boolean allowed;

        Client(int uid, int pid, IShizukuApplication app, String packageName, int apiVersion) {
            this.uid = uid;
            this.pid = pid;
            this.app = app;
            this.packageName = packageName;
            this.apiVersion = apiVersion;
        }

        void dispatchResult(int requestCode, boolean allowed) {
            Bundle b = new Bundle();
            b.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed);
            try {
                app.dispatchRequestPermissionResult(requestCode, b);
            } catch (Throwable tr) {
                LOGGER.w(tr, "dispatch to %s failed", packageName);
            }
        }
    }

    private final Handler mainHandler = new Handler(Looper.myLooper());
    private final List<Client> clients = new ArrayList<>();
    private final Policy policy = new Policy();
    private final InstallGuard installGuard = new InstallGuard(policy, this::askInstallerName);
    private final int managerAppId;

    private static void waitForSystemService(String name) {
        while (android.os.ServiceManager.getService(name) == null) {
            LOGGER.i("waiting for system service %s", name);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    public BrokerService() {
        HandlerUtil.setMainHandler(mainHandler);
        LOGGER.i("starting broker (uid %d, context %s)", OsUtils.getUid(), OsUtils.getSELinuxContext());
        waitForSystemService("package");
        waitForSystemService("activity");
        waitForSystemService("user");
        // Older policy files keyed Binder calls by transaction code; codes move between Android releases, names do not
        policy.renameKeys(Policy.CAP_BINDER, BinderNames::migrate);
        ApplicationInfo ai = PackageManagerApis.getApplicationInfoNoThrow(MANAGER_PACKAGE, 0, 0);
        if (ai == null) {
            LOGGER.e("manager app %s is not installed", MANAGER_PACKAGE);
            System.exit(50);
        }
        // The manager can change every app's policy: only trust the system app (or an update Android accepted for it,
        // which must carry the same signature), never a package that merely has the name
        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            LOGGER.e("manager app %s is not a system app; refusing to run", MANAGER_PACKAGE);
            System.exit(51);
        }
        managerAppId = ai.uid;
        BinderPusher.register(this);
        mainHandler.post(() -> BinderPusher.sendToAllClients(this));
    }

    // ------------------------------------------------------------------ identity helpers
    private boolean isManager(int uid) {
        return UserHandleCompat.getAppId(uid) == managerAppId;
    }

    private Client findClient(int uid, int pid) {
        synchronized (clients) {
            for (Client c : clients) {
                if (c.uid == uid && c.pid == pid) return c;
            }
        }
        return null;
    }

    /** Set while a command runs on behalf of an app that reached the broker through the fake-root socket (see SuServer). */
    private static final ThreadLocal<Integer> ACTING_UID = new ThreadLocal<>();

    private static int callingUid() {
        Integer acting = ACTING_UID.get();
        return acting != null ? acting : Binder.getCallingUid();
    }

    private String callerLabel() {
        Integer acting = ACTING_UID.get();
        if (acting != null) {
            Client c = findClientByUid(acting);
            return c != null ? c.packageName : String.valueOf(acting);
        }
        Client c = findClient(Binder.getCallingUid(), Binder.getCallingPid());
        return c != null ? c.packageName : String.valueOf(Binder.getCallingUid());
    }

    private Client findClientByUid(int uid) {
        synchronized (clients) {
            for (Client c : clients) {
                if (c.uid == uid && c.allowed) return c;
            }
        }
        return null;
    }

    private static android.os.IBinder token(Client c) {
        return c != null && c.app != null ? c.app.asBinder() : null;
    }

    // ------------------------------------------------------------------ su (fake root) consent

    /** How long an answer to the su question lasts, as the dialog sends it: seconds, or one of these. */
    private static final int SU_ALWAYS = 1_000_000_000, SU_DENY_ALWAYS = -2;
    private static final String SU_KEY = "su";
    private static final String SU_WHAT = "use su";
    private static final String SU_DETAIL = "su is Plait's stand-in for root on this phone: nothing runs as root. Every command "
            + "the app sends is still checked by Broker, with the same lists, questions and refusals as for any other app.";

    /** Timed "allow" answers: uid -> elapsedRealtime when they end. In memory only: a restart asks again. */
    private final Map<Integer, Long> suAllowedUntil = new HashMap<>();
    private final Map<Integer, Object> suLocks = new HashMap<>();
    private final Map<Integer, Client> suClients = new HashMap<>();

    /**
     * The app behind a fake-root request, once it has been allowed to use su; else null and [why] holds the reason.
     * An app that never used the Shizuku API has nothing to ask with, so the question is asked here, like Magisk's: allow
     * for a while or always, or deny. Being allowed to use the broker does not allow su; this is asked on its own.
     */
    private Client suClient(int uid, String[] why) {
        int userId = UserHandleCompat.getUserId(uid);
        List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(uid);
        if (packages.size() != 1) {
            why[0] = "apps that share a user id cannot use su";
            return null;
        }
        String pkg = packages.get(0);
        if (MANAGER_PACKAGE.equals(pkg)) {
            why[0] = "not for the manager app";
            return null;
        }
        Object lock;
        synchronized (suLocks) {
            lock = suLocks.computeIfAbsent(uid, k -> new Object());
        }
        synchronized (lock) { // one question at a time per app: parallel commands wait for the same answer
            String relay = Relays.reason(pkg, userId);
            if (relay != null) {
                policy.block(uid, pkg, relay);
                why[0] = relay;
                return null;
            }
            if (!policy.checkIdentity(uid, pkg, Identity.signingCert(pkg, userId))) {
                synchronized (suAllowedUntil) {
                    suAllowedUntil.remove(uid);
                }
                suClients.remove(uid);
            }
            Boolean remembered = policy.decision(uid, Policy.CAP_ASK, SU_KEY);
            if (Boolean.FALSE.equals(remembered)) {
                why[0] = "su is denied for this app in Broker";
                return null;
            }
            boolean allowed = Boolean.TRUE.equals(remembered);
            boolean rememberAlways = false;
            if (!allowed) {
                synchronized (suAllowedUntil) {
                    Long until = suAllowedUntil.get(uid);
                    allowed = until != null && SystemClock.elapsedRealtime() < until;
                }
            }
            if (!allowed) {
                policy.log(uid, pkg, "su", null, "asking");
                int answer = ask(uid, pkg, "su", SU_WHAT, SU_DETAIL);
                if (answer == SU_DENY_ALWAYS) {
                    policy.setCap(uid, Policy.CAP_ASK, SU_KEY, false);
                    policy.log(uid, pkg, "su", null, "denied for good");
                    why[0] = "su is denied for this app in Broker";
                    return null;
                }
                if (answer == SU_ALWAYS) {
                    rememberAlways = true; // saved below, once the app has its place in the policy
                    allowed = true;
                } else if (answer > 0) {
                    synchronized (suAllowedUntil) {
                        suAllowedUntil.put(uid, SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(answer));
                    }
                    allowed = true;
                }
                if (!allowed) {
                    policy.log(uid, pkg, "su", null, answer == InstallGuard.Asker.NO_ANSWER ? "no answer" : "denied");
                    why[0] = answer == InstallGuard.Asker.NO_ANSWER ? "no answer to the su question; try again" : "su was denied";
                    return null;
                }
                policy.log(uid, pkg, "su", null, answer == SU_ALWAYS ? "allowed for good" : "allowed for " + answer + " s");
            }
            // the app now has a place in the policy, so the usual lists (what its commands may do) apply to it
            Policy.Entry e = policy.find(uid);
            if (e == null || !e.allowed()) {
                if (!policy.setMode(uid, pkg, Policy.MODE_RESTRICTED)) {
                    why[0] = "this app is blocked in Broker";
                    return null;
                }
            }
            // after setMode: an app with no commands yet gets the default list when it is first set to restricted
            if (rememberAlways) policy.setCap(uid, Policy.CAP_ASK, SU_KEY, true);
            Client attached = findClientByUid(uid);
            if (attached != null) return attached;
            Client c = suClients.get(uid);
            if (c == null) {
                c = new Client(uid, -1, null, pkg, 0);
                c.allowed = true;
                suClients.put(uid, c);
            }
            return c;
        }
    }

    /**
     * Runs [cmd] for the app with [uid] exactly as its own newProcess call would: the fake-root socket's route in. The app
     * must be one the user has allowed to use su (asked here the first time); nothing else lets an app in.
     */
    IRemoteProcess runAs(int uid, String[] cmd) {
        String[] why = new String[1];
        Client c = suClient(uid, why);
        if (c == null) {
            policy.log(uid, null, "su", String.join(" ", cmd), "refused: " + why[0]);
            return new RemoteProcessHolder(new FakeProcess(126, "", "su: " + why[0] + "\n"), null, uid);
        }
        ACTING_UID.set(uid);
        try {
            return newProcessFor(c, uid, cmd, null, null);
        } finally {
            ACTING_UID.remove();
        }
    }

    /** Throws unless the caller is the daemon, the manager, or an attached client the user has allowed. */
    private Client requireAllowed(String func) {
        int uid = Binder.getCallingUid(), pid = Binder.getCallingPid();
        if (uid == OsUtils.getUid() || isManager(uid)) return null;
        Client c = findClient(uid, pid);
        if (c == null) {
            policy.log(uid, null, func, null, "refused: not attached");
            throw new SecurityException("Not an attached client");
        }
        if (!c.allowed) {
            policy.log(uid, c.packageName, func, null, "refused: no permission");
            throw new SecurityException("Permission denied for " + func);
        }
        return c;
    }

    private void requireManager(String func) {
        if (Binder.getCallingPid() == Os.getpid()) return;
        if (!isManager(Binder.getCallingUid())) {
            throw new SecurityException(func + " is for the manager app only");
        }
    }

    // ------------------------------------------------------------------ IShizukuService
    @Override
    public int getVersion() {
        requireAllowed("getVersion");
        return ShizukuApiConstants.SERVER_VERSION;
    }

    @Override
    public int getUid() {
        requireAllowed("getUid");
        return Os.getuid();
    }

    @Override
    public int checkPermission(String permission) throws RemoteException {
        requireAllowed("checkPermission");
        return PermissionManagerApis.checkPermission(permission, Os.getuid());
    }

    @Override
    public String getSELinuxContext() {
        requireAllowed("getSELinuxContext");
        return SELinux.getContext();
    }

    @Override
    public String getSystemProperty(String name, String defaultValue) {
        requireAllowed("getSystemProperty");
        return SystemProperties.get(name, defaultValue);
    }

    @Override
    public void setSystemProperty(String name, String value) {
        Client c = requireAllowed("setSystemProperty");
        if (c != null && !policy.check(Binder.getCallingUid(), c.packageName, Policy.CAP_PROPSET, name)) {
            policy.log(Binder.getCallingUid(), c.packageName, "setprop", name, "DENIED");
            throw new SecurityException("Not allowed to set property " + name);
        }
        policy.log(Binder.getCallingUid(), callerLabel(), "setprop", name, "allowed");
        SystemProperties.set(name, value);
    }

    @Override
    public IRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        return newProcessFor(requireAllowed("newProcess"), Binder.getCallingUid(), cmd, env, dir);
    }

    private IRemoteProcess newProcessFor(Client c, int uid, String[] cmd, String[] env, String dir) {
        if (c != null && cmd != null && cmd.length > 0 && cmd[0].equals("plait-overlay")) return overlayCommand(c, uid, cmd);
        // What is checked is exactly what runs: sh -c is unwrapped, the program resolved on the system partitions
        Exec.Command command;
        try {
            command = Exec.parse(cmd, Exec::resolveOnDevice);
        } catch (Exec.Refused r) {
            String shown = Arrays.toString(cmd);
            policy.log(uid, callerLabel(), "exec", shown, "refused: " + r.getMessage());
            return finished(c, 126, "", "plait-broker: refused (" + r.getMessage() + ")\n");
        }
        String key = command.key();
        SystemWrites.Plan write = SystemWrites.plan(command.argv, dir);
        if (write != null) return systemWrite(c, uid, key, command, write);
        // Always an explicit directory, so relative paths are judged against the one they are really read from
        if (dir == null) dir = "/";
        Classifier.Decision d = c != null
                ? Classifier.command(command.argv, dir, c.packageName, new DeviceApps(UserHandleCompat.getUserId(uid)))
                : null;
        String unlistedRefusal = d != null ? askIfUnlisted(c, uid, d) : null;
        if (unlistedRefusal != null) {
            policy.log(uid, c.packageName, "exec", key, "refused: " + unlistedRefusal);
            return finished(c, 126, "", "plait-broker: " + unlistedRefusal + "\n");
        }
        if (d != null && d.tier != Classifier.Tier.NORMAL) {
            String refusal = decide(c, uid, d, new ArrayList<>());
            if (refusal != null) {
                policy.log(uid, c.packageName, "exec", key, "refused: " + refusal);
                return finished(c, 126, "", "plait-broker: " + refusal + "\n");
            }
        } else if (c != null && !policy.check(uid, c.packageName, Policy.CAP_EXEC, key)) {
            policy.log(uid, c.packageName, "exec", key, "DENIED");
            return finished(c, 126, "", "plait-broker: not allowed for this app: " + key + "\n");
        }
        policy.log(uid, callerLabel(), "exec", key, "allowed" + (d != null && d.tier != Classifier.Tier.NORMAL ? " (" + d + ")" : ""));
        try {
            Process process = Runtime.getRuntime().exec(command.execArgv(), Exec.environment(env), dir != null ? new File(dir) : null);
            return new RemoteProcessHolder(process, token(c), callingUid());
        } catch (IOException e) {
            return finished(c, 127, "", "plait-broker: " + e.getMessage() + "\n");
        }
    }


    /** Targets whose colors affect the whole phone: asked about once, not listed per app. */
    private static final Set<String> SYSTEM_COLOR_TARGETS = Set.of("android", "com.android.systemui");

    /**
     * {@code plait-overlay register NAME TARGET} (colors on stdin) or {@code unregister NAME}: colors for other apps, what
     * Monet theming apps do as root. See Overlays.
     */
    private IRemoteProcess overlayCommand(Client c, int uid, String[] cmd) {
        String shown = String.join(" ", cmd);
        final Overlays.Request[] parsedHolder = new Overlays.Request[1];
        int userId = UserHandleCompat.getUserId(uid);
        try {
            if (cmd.length >= 2 && cmd[1].equals("unregister")) {
                parsedHolder[0] = Overlays.parse(Arrays.asList(cmd).subList(1, cmd.length), "");
            } else {
                // validate the target before reading any input; entries are parsed when they arrive
                if (cmd.length != 4 || !cmd[1].equals("register")) throw new IllegalArgumentException("usage: plait-overlay register NAME TARGET | unregister NAME");
            }
        } catch (IllegalArgumentException e) {
            policy.log(uid, c.packageName, "overlay", shown, "refused: " + e.getMessage());
            return finished(c, 2, "", "plait-overlay: " + e.getMessage() + "\n");
        }
        try {
            Process p = Overlays.fromStdin(stdin -> {
                try {
                    Overlays.Request r = parsedHolder[0] != null ? parsedHolder[0]
                            : Overlays.parse(Arrays.asList(cmd).subList(1, cmd.length), stdin);
                    if (r.register) {
                        Classifier.Decision d = !SYSTEM_COLOR_TARGETS.contains(r.target)
                                ? Classifier.Decision.scoped(Classifier.Scope.THEME, r.target)
                                : r.colorsOnly()
                                        ? Classifier.Decision.ask("system colors", "recolor the system and every app (Material You colors)")
                                        // sizes and switches of the system UI change how the whole phone behaves, not only how it looks
                                        : Classifier.Decision.gated(Classifier.Gate.SYSTEM, "change sizes and switches of the system UI (" + r.target + ")");
                        String refusal = decide(c, uid, d, new ArrayList<>());
                        if (refusal != null) {
                            policy.log(uid, c.packageName, "overlay", shown, "refused: " + refusal);
                            return refusal;
                        }
                    }
                    String err = applyOverlay(r, userId);
                    policy.log(uid, c.packageName, "overlay", shown + (r.register ? " (" + r.entries.size() + " colors)" : ""),
                            err == null ? "allowed" : "failed: " + err);
                    return err;
                } catch (IllegalArgumentException e) {
                    policy.log(uid, c.packageName, "overlay", shown, "refused: " + e.getMessage());
                    return e.getMessage();
                }
            });
            return new RemoteProcessHolder(p, token(c), callingUid());
        } catch (IOException e) {
            return finished(c, 1, "", "plait-overlay: " + e.getMessage() + "\n");
        }
    }

    /** Registers the overlay: here if Android allows it (PlaitOS), else through the root helper on a rooted test phone. */
    private static String applyOverlay(Overlays.Request r, int userId) {
        try {
            Overlays.direct(r, userId);
            return null;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String why = String.valueOf(cause.getMessage());
            if (why.contains("Non-root shell") && Overlays.helperRunning()) {
                try {
                    return Overlays.viaHelper(r, userId);
                } catch (Exception io) {
                    return String.valueOf(io.getMessage());
                }
            }
            return why.contains("Non-root shell")
                    ? "this system does not let the shell user recolor apps (needs PlaitOS, or the root helper module)" : why;
        }
    }

    /**
     * Refusals come back as a failed command, the way any shell reports them, instead of an exception: apps written for
     * a root shell often do not catch one and crash. The audit log still records why.
     */
    private IRemoteProcess finished(Client c, int exitCode, String out, String err) {
        return new RemoteProcessHolder(new FakeProcess(exitCode, out, err), token(c), callingUid());
    }

    private static final File OVERLAY_ROOT = new File("/data/user_de/0/com.android.shell/plait-overlay");

    /**
     * The system partitions are read-only (verified boot), so a write there never runs. If the app may "write to the
     * system" it gets success and its files are kept in its own overlay directory; otherwise the read-only error.
     */
    private IRemoteProcess systemWrite(Client c, int uid, String key, Exec.Command command, SystemWrites.Plan plan) {
        String pkg = c != null ? c.packageName : "uid-" + uid;
        if (c != null && plan.targets.size() == 1 && HostsFile.isTarget(plan.targets.get(0))
                && (plan.kind == SystemWrites.Kind.COPY && plan.sources.size() == 1 && !plan.intoDir || plan.kind == SystemWrites.Kind.STDIN)) {
            return hostsWrite(c, uid, plan);
        }
        if (c != null && !policy.check(uid, c.packageName, Policy.CAP_SYSTEM_WRITE, key)) {
            policy.log(uid, pkg, "system write", key, "refused: read-only");
            return finished(c, 1, "", SystemWrites.readOnlyError(command.argv, plan));
        }
        String note = SystemWrites.apply(plan, new File(OVERLAY_ROOT, pkg));
        policy.log(uid, pkg, "system write", key, "pretended" + (note != null ? " (" + note + ")" : ""));
        return finished(c, 0, "", "");
    }

    /**
     * A real write to the hosts overlay (the DNS blocklist), asked once per app. Only blocking entries are kept; see
     * HostsFile. Without the overlay (not set up at boot) the write fails honestly instead of being pretended.
     */
    private static final String HOSTS_DETAIL = "The hosts file is what every app's name lookups check first. "
            + "Only blocking entries are kept: the list can block names, never send them to another address.";

    private IRemoteProcess hostsWrite(Client c, int uid, SystemWrites.Plan plan) {
        String refusal = decide(c, uid, Classifier.Decision.ask("hosts", "replace the phone's DNS blocklist"), new ArrayList<>());
        if (refusal != null) {
            policy.log(uid, c.packageName, "hosts", null, "refused: " + refusal);
            return finished(c, 1, "", "plait-broker: " + refusal + "\n");
        }
        if (!HostsFile.active()) {
            policy.log(uid, c.packageName, "hosts", null, "refused: the hosts overlay is not active");
            return finished(c, 1, "", "plait-broker: the hosts overlay is not active on this phone (it is set up at boot)\n");
        }
        try {
            if (plan.kind == SystemWrites.Kind.STDIN) {
                Process p = HostsFile.fromStdin(result -> policy.log(uid, c.packageName, "hosts", "from stdin", result));
                return new RemoteProcessHolder(p, token(c), callingUid());
            }
            HostsFile.Filtered f = HostsFile.write(new java.io.File(plan.sources.get(0)));
            policy.log(uid, c.packageName, "hosts", plan.sources.get(0),
                    f.entries + " names" + (f.dropped > 0 ? ", " + f.dropped + " redirecting lines dropped" : ""));
            return finished(c, 0, "", "");
        } catch (IOException e) {
            policy.log(uid, c.packageName, "hosts", null, "failed: " + e.getMessage());
            return finished(c, 1, "", "plait-broker: hosts: " + e.getMessage() + "\n");
        }
    }

    @Override
    public int addUserService(IShizukuServiceConnection conn, Bundle args) {
        requireAllowed("addUserService");
        policy.log(Binder.getCallingUid(), callerLabel(), "userService", null, "denied: not supported by the prototype");
        throw new SecurityException("User services are not supported by this broker");
    }

    @Override
    public int removeUserService(IShizukuServiceConnection conn, Bundle args) {
        return 0;
    }

    @Override
    public void attachUserService(IBinder binder, Bundle options) {
        // user services are not supported
    }

    @Override
    public boolean checkSelfPermission() {
        int uid = Binder.getCallingUid(), pid = Binder.getCallingPid();
        if (uid == OsUtils.getUid() || isManager(uid)) return true;
        Client c = findClient(uid, pid);
        if (c == null) throw new IllegalStateException("Not an attached client");
        return c.allowed;
    }

    @Override
    public boolean shouldShowRequestPermissionRationale() {
        int uid = Binder.getCallingUid();
        Policy.Entry e = policy.find(uid);
        return e != null && e.denied();
    }

    @Override
    public void requestPermission(int requestCode) {
        int uid = Binder.getCallingUid(), pid = Binder.getCallingPid();
        Client c = findClient(uid, pid);
        if (c == null) throw new IllegalStateException("Not an attached client");
        if (c.allowed) {
            c.dispatchResult(requestCode, true);
            return;
        }
        Policy.Entry e = policy.find(uid);
        if (e != null && e.denied()) {
            c.dispatchResult(requestCode, false);
            return;
        }
        int userId = UserHandleCompat.getUserId(uid);
        ApplicationInfo ai = PackageManagerApis.getApplicationInfoNoThrow(c.packageName, 0, userId);
        if (ai == null) return;
        policy.log(uid, c.packageName, "requestPermission", null, "asking the user");
        Intent intent = new Intent(REQUEST_PERMISSION_ACTION)
                .setPackage(MANAGER_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                .putExtra("uid", uid)
                .putExtra("pid", pid)
                .putExtra("requestCode", requestCode)
                .putExtra("applicationInfo", ai);
        ActivityManagerApis.startActivityNoThrow(intent, null, userId);
    }

    // ------------------------------------------------------------------ questions a waiting call asks the user

    private static final class Question {
        final CountDownLatch answered = new CountDownLatch(1);
        volatile int answer = InstallGuard.Asker.NO_ANSWER;
    }

    private final Map<Integer, Question> questions = new HashMap<>();
    /** Apps with a question on screen. Each waiting question holds a Binder thread, so: one per app, a few overall. */
    private final Set<Integer> asking = new HashSet<>();
    private static final int MAX_QUESTIONS = 3;

    /** Sent to the manager, which turns it into a quiet notification (EventReceiver). */
    public static final String EVENT_ACTION = "app.plait.broker.intent.action.EVENT";
    private static final long NOTIFY_REPEAT_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private final Map<String, Long> notified = new HashMap<>();

    /**
     * Tells the user, quietly, that an app asked for something that now waits in Plait Broker. [kind] is "request" (a list
     * entry was added switched off) or "refused" (a call that is never allowed); [what] reads after "asked to".
     * The same notice for the same app is not repeated within ten minutes, and never floods.
     */
    private void notifyManager(String kind, int uid, String pkg, String what, String target) {
        String key = uid + "|" + kind + "|" + what + "|" + target;
        long now = SystemClock.elapsedRealtime();
        synchronized (notified) {
            Long last = notified.get(key);
            if (last != null && now - last < NOTIFY_REPEAT_MILLIS) return;
            if (notified.size() > 100) notified.clear();
            notified.put(key, now);
        }
        try {
            Intent intent = new Intent(EVENT_ACTION)
                    .setPackage(MANAGER_PACKAGE)
                    .putExtra("kind", kind).putExtra("uid", uid).putExtra("pkg", pkg)
                    .putExtra("what", what).putExtra("target", target);
            ActivityManagerApis.broadcastIntent(intent, UserHandleCompat.getUserId(uid));
        } catch (Exception e) {
            LOGGER.w(e, "could not notify the manager");
        }
    }
    private final AtomicInteger nextQuestion = new AtomicInteger();

    private int askInstallerName(int uid, String pkg, String installer) {
        return ask(uid, pkg, "installer", installer, null);
    }

    /**
     * Shows a question over the screen (QuestionActivity: "installer", "ask" or "gate") and waits, on the caller's Binder
     * thread, for the answer: InstallGuard.Asker values, or for a gate the seconds it may stay open.
     */
    private int ask(int uid, String pkg, String kind, String what, String detail) {
        int userId = UserHandleCompat.getUserId(uid);
        ApplicationInfo ai = PackageManagerApis.getApplicationInfoNoThrow(pkg, 0, userId);
        if (ai == null) return InstallGuard.Asker.NO_ANSWER;
        int id = nextQuestion.incrementAndGet();
        Question q = new Question();
        synchronized (questions) {
            if (asking.contains(uid) || asking.size() >= MAX_QUESTIONS) return InstallGuard.Asker.NO_ANSWER;
            asking.add(uid);
            questions.put(id, q);
        }
        try {
            Intent intent = new Intent(ASK_ACTION)
                    .setPackage(MANAGER_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    .putExtra("question", id)
                    .putExtra("kind", kind)
                    .putExtra("what", what)
                    .putExtra("detail", detail)
                    .putExtra("timeoutMillis", TimeUnit.SECONDS.toMillis(ANSWER_TIMEOUT_SECONDS))
                    .putExtra("applicationInfo", ai);
            ActivityManagerApis.startActivityNoThrow(intent, null, userId);
            q.answered.await(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return q.answer;
        } catch (InterruptedException e) {
            return InstallGuard.Asker.NO_ANSWER;
        } finally {
            synchronized (questions) {
                questions.remove(id);
                asking.remove(uid);
            }
        }
    }

    // ------------------------------------------------------------------ tiers (Classifier, BinderRules)

    /** Open gates: "uid:gate" -> elapsedRealtime when it closes. Only in memory: never remembered. */
    private final Map<String, Long> openGates = new HashMap<>();

    /**
     * Applies a Classifier/BinderRules decision for an app's call. Returns null if the call may go ahead (without the
     * per-app command/Binder capability: the list, the dialog or the gate was the user's consent), else why not.
     */
    private String decide(Client c, int uid, Classifier.Decision d, List<String> targets) {
        String pkg = c.packageName;
        switch (d.tier) {
            case NEVER:
                notifyManager("refused", uid, pkg, d.reason, null);
                return "never allowed through the broker: " + d.reason;
            case GATED: {
                // Discouraged on purpose: off globally by default, and off for each app until the user switches it on
                if (!policy.gatesEnabled()) return "gated functions are switched off in Broker";
                boolean gateKnown = policy.decision(uid, Policy.CAP_GATE, d.gate.id()) != null;
                if (!policy.gateAllowed(uid, pkg, d.gate.id())) {
                    if (!gateKnown) notifyManager("request", uid, pkg, d.gate.description, null);
                    return "not switched on for this app in Broker: " + d.gate.description;
                }
                String gate = uid + ":" + d.gate.id();
                synchronized (openGates) {
                    Long closes = openGates.get(gate);
                    if (closes != null && SystemClock.elapsedRealtime() < closes) return null;
                    openGates.remove(gate);
                }
                policy.log(uid, pkg, "gate", d.gate.id(), "asking: " + d.reason);
                int seconds = ask(uid, pkg, "gate", d.gate.description, d.reason);
                if (seconds <= 0) return "not allowed: " + d.gate.description;
                seconds = Math.min(seconds, MAX_GATE_SECONDS);
                synchronized (openGates) {
                    openGates.put(gate, SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(seconds));
                }
                policy.log(uid, pkg, "gate", d.gate.id(), "opened for " + seconds + " s");
                return null;
            }
            case ASK: {
                Boolean allowed = policy.decision(uid, Policy.CAP_ASK, d.key);
                if (allowed == null) {
                    int answer = ask(uid, pkg, "ask", d.reason, d.key.equals("hosts") ? HOSTS_DETAIL : d.key);
                    if (answer != InstallGuard.Asker.NO_ANSWER) policy.setCap(uid, Policy.CAP_ASK, d.key, answer > 0);
                    allowed = answer > 0;
                }
                return allowed ? null : "not allowed: " + d.reason;
            }
            case SCOPED: {
                List<String> all = targets.isEmpty() ? Collections.singletonList(d.target) : targets;
                int userId = UserHandleCompat.getUserId(uid);
                for (String target : all) {
                    String key = d.scope.id() + " " + target;
                    boolean known = policy.decision(uid, Policy.CAP_SCOPED, key) != null;
                    if (!policy.scopedAllowed(uid, pkg, key, Identity.signingCert(target, userId))) {
                        if (!known) notifyManager("request", uid, pkg, d.scope.description, target);
                        return pkg + " may not " + d.scope.description + " " + target
                                + " (listed in Broker, switched off; only you can switch it on)";
                    }
                }
                return null;
            }
            case INSTALL:
                return policy.check(uid, pkg, Policy.CAP_INSTALL, Policy.ANY_PACKAGE)
                        ? null : "installing apps is not allowed for this app (Installs: any app)";
            default:
                return null;
        }
    }

    /**
     * Permissive mode allows what it sees, but not an unlisted call (an unknown system service or interface): that is
     * asked about first, once per app and service. Restricted mode lists it as usual. Null if it may go on.
     */
    private String askIfUnlisted(Client c, int uid, Classifier.Decision d) {
        if (!d.unlisted || c == null) return null;
        Policy.Entry e = policy.find(uid);
        if (e == null || !Policy.MODE_PERMISSIVE.equals(e.mode)) return null;
        return decide(c, uid, d.askInstead(), new ArrayList<>());
    }

    /** Targets that can never be put on an app's list: the phone's core, the broker, anything running as a system uid. */
    private static final Set<String> PROTECTED_TARGETS = Set.of(
            "android", "com.android.systemui", "com.android.settings", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.shell", "com.android.phone", "com.android.launcher3", "com.google.android.apps.nexuslauncher",
            "com.android.providers.settings", "app.seamlessupdate.client", MANAGER_PACKAGE);

    private static String protectedTarget(String pkg, int userId, boolean colorsOnly) {
        // recoloring is harmless on the phone's own apps; the whole-system targets are asked about instead (see overlayCommand)
        if (colorsOnly ? pkg.equals(MANAGER_PACKAGE) || SYSTEM_COLOR_TARGETS.contains(pkg) : PROTECTED_TARGETS.contains(pkg)) {
            return "a core part of the phone";
        }
        ApplicationInfo ai = PackageManagerApis.getApplicationInfoNoThrow(pkg, 0, userId);
        if (ai == null) return "not installed";
        if (!colorsOnly && UserHandleCompat.getAppId(ai.uid) < android.os.Process.FIRST_APPLICATION_UID) return "runs as a system user";
        return null;
    }

    @Override
    public void attachApplication(IShizukuApplication application, Bundle args) {
        if (application == null || args == null) return;
        String pkg = args.getString(ATTACH_APPLICATION_PACKAGE_NAME);
        if (pkg == null) return;
        int apiVersion = args.getInt(ATTACH_APPLICATION_API_VERSION, -1);
        int pid = Binder.getCallingPid(), uid = Binder.getCallingUid();
        if (!PackageManagerApis.getPackagesForUidNoThrow(uid).contains(pkg)) {
            throw new SecurityException("Package " + pkg + " does not belong to uid " + uid);
        }
        boolean manager = MANAGER_PACKAGE.equals(pkg);
        // No broker behind the broker: an app that passes access on to other apps is refused everything
        String relay = manager ? null : Relays.reason(pkg, UserHandleCompat.getUserId(uid));
        if (relay != null) {
            policy.block(uid, pkg, relay);
            policy.log(uid, pkg, "attach", null, "BLOCKED: " + relay);
            Bundle refused = new Bundle();
            refused.putInt(BIND_APPLICATION_SERVER_UID, OsUtils.getUid());
            refused.putInt(BIND_APPLICATION_SERVER_VERSION, ShizukuApiConstants.SERVER_VERSION);
            refused.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, false);
            refused.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
            try {
                application.bindApplication(refused);
            } catch (Throwable tr) {
                LOGGER.w(tr, "bindApplication failed");
            }
            return;
        }
        if (!manager && !policy.checkIdentity(uid, pkg, Identity.signingCert(pkg, UserHandleCompat.getUserId(uid)))) {
            policy.log(uid, pkg, "attach", null, "signed by someone else than before: earlier decisions dropped");
        }
        Client client = findClient(uid, pid);
        if (client == null) {
            client = new Client(uid, pid, application, pkg, apiVersion);
            Policy.Entry e = policy.find(uid);
            client.allowed = e != null && e.allowed();
            final Client added = client;
            try {
                application.asBinder().linkToDeath(() -> {
                    synchronized (clients) {
                        clients.remove(added);
                    }
                }, 0);
            } catch (RemoteException ex) {
                return;
            }
            synchronized (clients) {
                clients.add(client);
            }
        }
        Bundle reply = new Bundle();
        reply.putInt(BIND_APPLICATION_SERVER_UID, OsUtils.getUid());
        reply.putInt(BIND_APPLICATION_SERVER_VERSION, apiVersion == -1 ? 12 : ShizukuApiConstants.SERVER_VERSION);
        reply.putString(BIND_APPLICATION_SERVER_SECONTEXT, OsUtils.getSELinuxContext());
        reply.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION);
        if (!manager) {
            reply.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, client.allowed);
            reply.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
        }
        try {
            application.bindApplication(reply);
        } catch (Throwable tr) {
            LOGGER.w(tr, "bindApplication failed");
        }
        policy.log(uid, pkg, "attach", null, manager ? "manager" : (client.allowed ? "allowed" : "not allowed yet"));
    }

    @Override
    public void exit() {
        requireManager("exit");
        System.exit(0);
    }

    @Override
    public void dispatchPackageChanged(Intent intent) {
        // nothing cached per package
    }

    @Override
    public boolean isHidden(int uid) {
        return false;
    }

    @Override
    public void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, Bundle data) {
        if (!isManager(Binder.getCallingUid())) {
            LOGGER.w("dispatchPermissionConfirmationResult from a non-manager");
            return;
        }
        if (data == null) return;
        boolean allowed = data.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED);
        boolean onetime = data.getBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME);
        String mode = data.getString(EXTRA_MODE, Policy.MODE_RESTRICTED);
        String pkg = null;
        synchronized (clients) {
            for (Client c : clients) {
                if (c.uid == requestUid) pkg = c.packageName;
            }
        }
        policy.setMode(requestUid, pkg, allowed ? mode : Policy.MODE_DENIED);
        if (pkg != null) policy.checkIdentity(requestUid, pkg, Identity.signingCert(pkg, UserHandleCompat.getUserId(requestUid)));
        // What the app declared in its manifest and the user saw in the dialog: switched on with the consent, before
        // the app hears the answer (the broker's tiers still apply to each use)
        String[] declared = allowed ? data.getStringArray(EXTRA_DECLARED) : null;
        if (declared != null) {
            for (String d : declared) {
                int nl = d.indexOf('\n');
                String type = nl > 0 ? d.substring(0, nl) : "";
                if (type.equals(Policy.CAP_EXEC) || type.equals(Policy.CAP_ASK)) policy.setCap(requestUid, type, d.substring(nl + 1), true);
            }
        }
        policy.log(requestUid, pkg, "consent", allowed ? mode : "denied",
                "recorded" + (declared != null && declared.length > 0 ? " with " + declared.length + " declared" : ""));
        synchronized (clients) {
            for (Client c : clients) {
                if (c.uid == requestUid) {
                    c.allowed = allowed;
                    if (c.pid == requestPid) c.dispatchResult(requestCode, allowed);
                }
            }
        }
    }

    @Override
    public int getFlagsForUid(int uid, int mask) {
        if (!isManager(Binder.getCallingUid())) return 0;
        Policy.Entry e = policy.find(uid);
        if (e == null) return 0;
        return (e.allowed() ? 2 : 0) | (e.denied() ? 4 : 0);
    }

    @Override
    public void updateFlagsForUid(int uid, int mask, int value) {
        if (!isManager(Binder.getCallingUid())) return;
        synchronized (clients) {
            for (Client c : clients) {
                if (c.uid == uid) c.allowed = (value & 2) != 0;
            }
        }
        if ((value & 2) != 0) policy.setMode(uid, null, Policy.MODE_RESTRICTED);
        else if ((value & 4) != 0) policy.setMode(uid, null, Policy.MODE_DENIED);
        else policy.remove(uid);
    }

    // ------------------------------------------------------------------ Binder wrapper (system service calls)
    private void transactRemote(Parcel data, Parcel reply, int flags) throws RemoteException {
        Client c = requireAllowed("transactRemote");
        int uid = Binder.getCallingUid();
        IBinder target = data.readStrongBinder();
        int code = data.readInt();
        String descriptor = target.getInterfaceDescriptor();
        String key = BinderNames.key(descriptor, code);
        int targetFlags = (c != null && c.apiVersion >= 13) ? data.readInt() : flags;
        Parcel newData = Parcel.obtain();
        Parcel send = newData;
        try {
            newData.appendFrom(data, data.dataPosition(), data.dataAvail());
            if (c != null) {
                // Same tiers as commands: pm, am and appops are only front-ends to these services
                List<String> targets = new ArrayList<>();
                Classifier.Decision d = BinderRules.classify(descriptor, key, newData, c.packageName,
                        new DeviceApps(UserHandleCompat.getUserId(uid)), targets);
                String refusal = askIfUnlisted(c, uid, d);
                if (refusal == null) {
                    refusal = d.tier != Classifier.Tier.NORMAL ? decide(c, uid, d, targets)
                            : policy.check(uid, c.packageName, Policy.CAP_BINDER, key) ? null : "not allowed for this app";
                }
                if (refusal != null) {
                    policy.log(uid, c.packageName, "binder", key, "refused: " + refusal);
                    throw new SecurityException("Binder call refused: " + key + ": " + refusal);
                }
            }
            // The call runs as the shell user: installer calls get extra checks (and may be rebuilt)
            if (c != null) send = installGuard.before(uid, c.packageName, key, newData);
            long id = Binder.clearCallingIdentity();
            try {
                target.transact(code, send, reply, targetFlags);
            } finally {
                Binder.restoreCallingIdentity(id);
            }
            if (c != null) installGuard.after(uid, key, reply);
            policy.log(uid, callerLabel(), "binder", key, "allowed");
        } finally {
            if (send != newData) send.recycle();
            newData.recycle();
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == ShizukuApiConstants.BINDER_TRANSACTION_transact) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            transactRemote(data, reply, flags);
            return true;
        }
        if (code == 14 /* attachApplication, API <= 12 clients */) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            IBinder binder = data.readStrongBinder();
            String packageName = data.readString();
            Bundle args = new Bundle();
            args.putString(ATTACH_APPLICATION_PACKAGE_NAME, packageName);
            args.putInt(ATTACH_APPLICATION_API_VERSION, -1);
            attachApplication(IShizukuApplication.Stub.asInterface(binder), args);
            reply.writeNoException();
            return true;
        }
        switch (code) {
            case TX_DUMP:
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
                requireManager("dump");
                int dumpUser = UserHandleCompat.getUserId(Binder.getCallingUid());
                policy.pruneScoped(target -> PackageManagerApis.getApplicationInfoNoThrow(target, 0, dumpUser) != null);
                reply.writeNoException();
                reply.writeString(policy.dumpJson(HostsFile.active(), HostsFile.entries()));
                return true;
            case TX_SET_MODE:
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
                requireManager("setMode");
                int uid = data.readInt();
                String pkg = data.readString();
                String mode = data.readString();
                if (!policy.setMode(uid, pkg, mode)) {
                    policy.log(uid, pkg, "mode", mode, "refused: blocked apps stay blocked");
                    reply.writeNoException();
                    return true;
                }
                synchronized (clients) {
                    for (Client c : clients) {
                        if (c.uid == uid) c.allowed = !Policy.MODE_DENIED.equals(mode);
                    }
                }
                policy.log(uid, pkg, "mode", mode, "set by manager");
                reply.writeNoException();
                return true;
            case TX_SET_CAP:
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
                requireManager("setCap");
                int cuid = data.readInt();
                String type = data.readString();
                String key = data.readString();
                boolean on = data.readInt() != 0;
                if (Policy.CAP_SCOPED.equals(type) && on) {
                    // "freeze com.example": only for that one app, as signed now, and never the phone's core apps
                    String scopedTarget = key.substring(key.indexOf(' ') + 1);
                    int targetUser = UserHandleCompat.getUserId(cuid);
                    String why = protectedTarget(scopedTarget, targetUser, key.startsWith("theme "));
                    String cert = why == null ? Identity.signingCert(scopedTarget, targetUser) : null;
                    if (cert == null) {
                        String reason = why != null ? why : "its signature could not be read";
                        policy.log(cuid, null, "capability", type + ": " + key, "refused: " + reason);
                        reply.writeException(new IllegalStateException("Cannot switch on " + scopedTarget + ": " + reason));
                        return true;
                    }
                    policy.enableScoped(cuid, key, cert);
                } else {
                    policy.setCap(cuid, type, key, on);
                }
                policy.log(cuid, null, "capability", type + ": " + key, on ? "turned on" : "turned off");
                reply.writeNoException();
                return true;
            case TX_ANSWER:
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
                requireManager("answer");
                int qid = data.readInt();
                int answer = data.readInt();
                Question q;
                synchronized (questions) {
                    q = questions.get(qid);
                }
                if (q != null) {
                    q.answer = answer;
                    q.answered.countDown();
                }
                reply.writeNoException();
                return true;
            case TX_RESET_HOSTS: {
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
                requireManager("resetHosts");
                try {
                    HostsFile.reset();
                    policy.log(OsUtils.getUid(), "Broker", "hosts", null, "reset to stock");
                    reply.writeNoException();
                } catch (IOException e) {
                    reply.writeException(new IllegalStateException("Could not reset the hosts file: " + e.getMessage()));
                }
                return true;
            }
            case TX_SET_GATES: {
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
                requireManager("setGates");
                boolean enabled = data.readInt() != 0;
                policy.setGatesEnabled(enabled);
                if (!enabled) {
                    synchronized (openGates) {
                        openGates.clear();
                    }
                }
                policy.log(OsUtils.getUid(), "Broker", "gated functions", null, enabled ? "switched on" : "switched off");
                reply.writeNoException();
                return true;
            }
            case TX_REMOVE:
                data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
                requireManager("remove");
                policy.remove(data.readInt());
                reply.writeNoException();
                return true;
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }
}
