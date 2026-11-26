package app.plait.broker.server;

import android.util.AtomicFile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.UserHandleCompat;

/**
 * Per-app policy, stored by the (shell-uid) daemon and edited through the manager app.
 *
 * <p>Each app has a mode:
 * <ul>
 *   <li>{@code denied}: no access;</li>
 *   <li>{@code restricted}: only capabilities marked allowed are granted; anything else is refused and listed so the user can approve it;</li>
 *   <li>{@code permissive}: everything is granted and recorded as a capability; the user can switch any recorded capability off,
 *       and later flip the app to restricted so that only what was seen (and left on) keeps working.</li>
 * </ul>
 * A capability is a (type, key) pair: {@code exec} (an argument list, shell-quoted; see {@link Exec} for the rule syntax),
 * {@code binder} (interface descriptor and method, see {@link BinderNames}), {@code install} (a package an app may install
 * through the package installer, see {@link InstallGuard}; {@code *} = any, including undeclared), {@code installer} (another
 * package an app may be recorded as having installed through, always asked about) or {@code propset} (a system property name).
 */
public class Policy {
    public static final String MODE_DENIED = "denied";
    public static final String MODE_RESTRICTED = "restricted";
    public static final String MODE_PERMISSIVE = "permissive";
    /** Apps that pass broker access on to other apps (see {@link Relays}): refused everything, not changeable. */
    public static final String MODE_BLOCKED = "blocked";

    public static final String CAP_EXEC = "exec";
    public static final String CAP_BINDER = "binder";
    public static final String CAP_PROPSET = "propset";
    public static final String CAP_INSTALL = "install";
    /** Install key for any package, including sessions that do not declare one (then nothing narrower can be checked). */
    public static final String ANY_PACKAGE = "*";
    /** Commands that write to the read-only system partitions, answered as success without changing it (SystemWrites). */
    public static final String CAP_SYSTEM_WRITE = "systemwrite";
    /** Answers to the warning dialog for device-wide or privacy-relevant functions (Classifier ASK), per app and key. */
    public static final String CAP_ASK = "ask";
    /**
     * One function on one other app ("freeze com.example"), Classifier SCOPED. Recorded switched off when the app asks;
     * only the manager switches it on, which pins the target's signing certificate ({@link Cap#targetCert}).
     */
    public static final String CAP_SCOPED = "scoped";
    /**
     * Gated functions an app may ask for (Classifier GATED: input injection, screen capture, ...), one entry per gate.
     * Recorded switched off when the app first tries; only the user switches it on, and only while gated functions are
     * switched on globally ({@link #gatesEnabled()}, off by default). Even then every use asks for a short time window.
     */
    public static final String CAP_GATE = "gate";
    /** Installer names an app may install as (e.g. the Play Store). Asked about in every mode, never recorded silently. */
    public static final String CAP_INSTALLER = "installer";

    /** Commands a freshly restricted app may run ({@link Exec} rule syntax: '...' matches any remaining arguments). */
    public static final List<String> DEFAULT_EXEC = Arrays.asList(
            "ip neigh",
            "ip neigh show ...",
            "ip -4 neigh show",
            "ip -6 neigh show",
            "cat /proc/net/arp");

    public static class Cap {
        public String type;
        public String key;
        public boolean allowed;
        public int uses;
        public int blocked;
        public long last;
        /** CAP_SCOPED: the target's signing certificate when the user switched it on. */
        public String targetCert;
    }

    public static class Entry {
        public int uid;
        public List<String> packages = new ArrayList<>();
        public String mode = MODE_DENIED;
        /** SHA-256 of the signing certificate the decisions were made for (see {@link #checkIdentity}). */
        public String cert;
        /** Why the app is blocked, shown in the manager. */
        public String note;
        /**
         * The manager's "Show Shizuku" switch, off: the Shizuku placeholder stays hidden from this app although it is
         * allowed. False (the default, and what older files read as) means an allowed app sees it.
         */
        public boolean shizukuHidden;
        public List<Cap> caps = new ArrayList<>();

        public boolean allowed() {
            return MODE_RESTRICTED.equals(mode) || MODE_PERMISSIVE.equals(mode);
        }

        public boolean denied() {
            return MODE_DENIED.equals(mode) || MODE_BLOCKED.equals(mode);
        }

        Cap find(String type, String key) {
            for (Cap c : caps) {
                if (c.type.equals(type) && c.key.equals(key)) return c;
            }
            return null;
        }

        /** The allowed capability whose key matches [key] (exact, or as an exec rule), if any. */
        Cap matchAllowed(String type, String key) {
            List<String> argv = null;
            if (CAP_EXEC.equals(type)) {
                try {
                    argv = Exec.split(key);
                } catch (Exec.Refused r) {
                    return null; // keys come from Exec.join, so this does not happen
                }
            }
            for (Cap c : caps) {
                if (!c.type.equals(type) || !c.allowed) continue;
                if (argv != null ? matchesRule(c.key, argv) : CAP_INSTALL.equals(type) ? matchesPackage(c.key, key) : c.key.equals(key)) {
                    return c;
                }
            }
            return null;
        }
    }

    private static class Data {
        int version = VERSION;
        List<Entry> entries = new ArrayList<>();
        /** Master switch for gated functions; off by default and in files from before it existed. */
        boolean gatesEnabled;
    }

    /** 3: exec keys are argument lists (Exec syntax); 2 used joined command lines with a trailing-'*' prefix match. */
    private static final int VERSION = 3;

    private static final Map<String, Exec.Rule> RULES = new HashMap<>();

    /** Install rules: a package name, a prefix ending in ".*", or "*" for any. */
    private static boolean matchesPackage(String rule, String pkg) {
        if (rule.equals(ANY_PACKAGE) || rule.equals(pkg)) return true;
        return rule.endsWith(".*") && !pkg.equals(ANY_PACKAGE) && pkg.startsWith(rule.substring(0, rule.length() - 1));
    }

    private static synchronized boolean matchesRule(String rule, List<String> argv) {
        Exec.Rule r;
        if (RULES.containsKey(rule)) {
            r = RULES.get(rule);
        } else {
            try {
                r = Exec.rule(rule);
            } catch (Exec.Refused e) {
                LOGGER.w("ignoring invalid exec rule [%s]: %s", rule, e.getMessage());
                r = null;
            }
            RULES.put(rule, r);
        }
        return r != null && r.matches(argv);
    }

    private static final Logger LOGGER = new Logger("Policy");
    private static final File FILE = new File("/data/user_de/0/com.android.shell/plait-broker.json");
    private static final int AUDIT_MAX = 200;
    private static final int CAPS_MAX = 300;

    private final AtomicFile atomicFile = new AtomicFile(FILE);
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Data data;
    private final LinkedList<String> audit = new LinkedList<>();

    public Policy() {
        Data loaded = null;
        try (FileInputStream in = atomicFile.openRead()) {
            loaded = gson.fromJson(new InputStreamReader(in), Data.class);
        } catch (Throwable tr) {
            LOGGER.i("no usable policy file yet (%s); starting empty", tr.getClass().getSimpleName());
        }
        data = loaded != null && loaded.entries != null ? loaded : new Data();
        // version 1 files used "tier" (scoped/full); this prototype simply starts those apps over
        data.entries.removeIf(e -> e.mode == null || e.caps == null);
        if (data.version < 3) {
            for (Entry e : data.entries) {
                for (Cap c : e.caps) {
                    if (CAP_EXEC.equals(c.type)) c.key = migrateExecKey(c.key);
                }
            }
            data.version = VERSION;
            save();
        }
    }

    /** A version 2 exec key (joined command line, maybe a trailing-'*' prefix) in Exec syntax. */
    static String migrateExecKey(String old) {
        String line = old.trim();
        boolean prefix = line.endsWith("*");
        if (prefix) line = line.substring(0, line.length() - 1).trim();
        List<String> argv = null;
        if (line.startsWith("sh -c ")) {
            try {
                argv = Exec.script(line.substring(6));
            } catch (Exec.Refused r) {
                argv = Arrays.asList("sh", "-c", line.substring(6)); // stays visible, never matches: shells are unwrapped
            }
        }
        if (argv == null) argv = Arrays.asList(line.split(" +"));
        return Exec.join(argv) + (prefix ? " ..." : "");
    }

    public synchronized boolean isAllowed(int uid) {
        Entry entry = find(uid);
        return entry != null && entry.allowed();
    }

    public synchronized Entry find(int uid) {
        for (Entry e : data.entries) {
            if (e.uid == uid) return e;
        }
        return null;
    }

    public synchronized Entry findOrCreate(int uid, String pkg) {
        Entry e = find(uid);
        if (e == null) {
            e = new Entry();
            e.uid = uid;
            data.entries.add(e);
        }
        if (pkg != null && !e.packages.contains(pkg)) e.packages.add(pkg);
        return e;
    }

    /** Marks an app as a broker for other apps: everything is refused from now on, whatever its mode was. */
    public synchronized void block(int uid, String pkg, String reason) {
        Entry e = findOrCreate(uid, pkg);
        e.mode = MODE_BLOCKED;
        e.note = reason;
        save();
    }

    /** Sets an app's mode; a blocked app stays blocked (only forgetting it clears that). Returns false if refused. */
    public synchronized boolean setMode(int uid, String pkg, String mode) {
        Entry existing = find(uid);
        if (existing != null && MODE_BLOCKED.equals(existing.mode) && !MODE_BLOCKED.equals(mode)) return false;
        Entry e = findOrCreate(uid, pkg);
        e.mode = mode;
        if (MODE_RESTRICTED.equals(mode) && e.caps.isEmpty()) {
            for (String cmd : DEFAULT_EXEC) {
                Cap c = new Cap();
                c.type = CAP_EXEC;
                c.key = cmd;
                c.allowed = true;
                e.caps.add(c);
            }
        }
        save();
        return true;
    }

    /**
     * Ties an app's decisions to its signing certificate. Android can give a freed uid to the next app installed, so an
     * entry whose certificate no longer matches (or, for an entry made before pinning, whose package differs) is dropped:
     * that app starts over and has to ask. Pins the certificate when missing. Returns false if the entry was dropped.
     */
    public synchronized boolean checkIdentity(int uid, String pkg, String cert) {
        Entry e = find(uid);
        if (e == null || cert == null) return true; // nothing to protect, or unreadable: never drop on a transient failure
        boolean same = e.cert != null ? e.cert.equals(cert) : e.packages.isEmpty() || e.packages.contains(pkg);
        if (!same) {
            data.entries.remove(e);
            save();
            return false;
        }
        if (e.cert == null || !e.packages.contains(pkg)) {
            e.cert = cert;
            if (!e.packages.contains(pkg)) e.packages.add(pkg);
            save();
        }
        return true;
    }

    /**
     * Whether a scoped entry ("freeze com.example") is on for this app and still names the same target app. A request
     * for an unknown target is recorded switched off, so it shows up for the user; it is never switched on here.
     */
    public synchronized boolean scopedAllowed(int uid, String pkg, String key, String targetCert) {
        Entry e = findOrCreate(uid, pkg);
        Cap c = e.find(CAP_SCOPED, key);
        long now = System.currentTimeMillis();
        if (c == null) {
            if (e.caps.size() >= CAPS_MAX) return false;
            c = new Cap();
            c.type = CAP_SCOPED;
            c.key = key;
            e.caps.add(c);
        }
        c.last = now;
        boolean ok = c.allowed && c.targetCert != null && c.targetCert.equals(targetCert);
        if (ok) {
            c.uses++;
        } else {
            if (c.allowed) c.allowed = false; // the target was replaced by a differently signed app: off again
            c.blocked++;
        }
        save();
        return ok;
    }

    public synchronized boolean gatesEnabled() {
        return data.gatesEnabled;
    }

    public synchronized void setGatesEnabled(boolean enabled) {
        data.gatesEnabled = enabled;
        save();
    }

    /** Whether this app's entry for [gate] is on. An unknown gate is recorded switched off, never switched on here. */
    public synchronized boolean gateAllowed(int uid, String pkg, String gate) {
        Entry e = findOrCreate(uid, pkg);
        Cap c = e.find(CAP_GATE, gate);
        if (c == null) {
            if (e.caps.size() >= CAPS_MAX) return false;
            c = new Cap();
            c.type = CAP_GATE;
            c.key = gate;
            e.caps.add(c);
        }
        c.last = System.currentTimeMillis();
        if (c.allowed) c.uses++;
        else c.blocked++;
        save();
        return c.allowed;
    }

    /** Switches a scoped entry on for the target signed with [targetCert] (manager only, checked by the caller). */
    public synchronized void enableScoped(int uid, String key, String targetCert) {
        Entry e = find(uid);
        if (e == null) return;
        Cap c = e.find(CAP_SCOPED, key);
        if (c == null) {
            c = new Cap();
            c.type = CAP_SCOPED;
            c.key = key;
            e.caps.add(c);
        }
        c.allowed = true;
        c.targetCert = targetCert;
        save();
    }

    /** The user's recorded answer for a capability that is always asked about: true, false, or null if never asked. */
    public synchronized Boolean decision(int uid, String type, String key) {
        Entry e = find(uid);
        Cap c = e != null ? e.find(type, key) : null;
        return c != null ? c.allowed : null;
    }

    public synchronized void setCap(int uid, String type, String key, boolean allowed) {
        Entry e = find(uid);
        if (e == null) return;
        Cap c = e.find(type, key);
        if (c == null) {
            c = new Cap();
            c.type = type;
            c.key = key;
            e.caps.add(c);
        }
        c.allowed = allowed;
        save();
    }

    /** Drops switched-off, never-used scoped entries whose target app is no longer installed. */
    public synchronized void pruneScoped(java.util.function.Predicate<String> installed) {
        boolean changed = false;
        for (Entry e : data.entries) {
            changed |= e.caps.removeIf(c -> c.type.equals(CAP_SCOPED) && !c.allowed && c.uses == 0
                    && !installed.test(c.key.substring(c.key.indexOf(' ') + 1)));
        }
        if (changed) save();
    }

    /** Rewrites the keys of one capability type (e.g. binder codes to method names), merging any that become equal. */
    public synchronized void renameKeys(String type, UnaryOperator<String> rename) {
        boolean changed = false;
        for (Entry e : data.entries) {
            List<Cap> kept = new ArrayList<>();
            for (Cap c : e.caps) {
                if (c.type.equals(type)) {
                    String renamed = rename.apply(c.key);
                    if (!renamed.equals(c.key)) {
                        c.key = renamed;
                        changed = true;
                    }
                    Cap same = null;
                    for (Cap k : kept) {
                        if (k.type.equals(type) && k.key.equals(c.key)) same = k;
                    }
                    if (same != null) {
                        same.allowed |= c.allowed;
                        same.uses += c.uses;
                        same.blocked += c.blocked;
                        same.last = Math.max(same.last, c.last);
                        continue;
                    }
                }
                kept.add(c);
            }
            e.caps = kept;
        }
        if (changed) save();
    }

    public synchronized void remove(int uid) {
        Entry e = find(uid);
        if (e != null) {
            data.entries.remove(e);
            save();
        }
    }

    /**
     * Decides whether the app may use a capability, and records the use. Permissive mode records new capabilities as allowed;
     * restricted mode records unknown ones as blocked so the user can see and approve them.
     */
    public synchronized boolean check(int uid, String pkg, String type, String key) {
        Entry e = find(uid);
        if (e == null || e.denied()) return false;
        long now = System.currentTimeMillis();
        boolean permissive = MODE_PERMISSIVE.equals(e.mode);

        Cap match = e.matchAllowed(type, key);
        if (match != null) {
            match.uses++;
            match.last = now;
            save();
            return true;
        }
        Cap exact = e.find(type, key);
        if (exact == null) {
            if (e.caps.size() >= CAPS_MAX) return permissive; // never grow without bound
            exact = new Cap();
            exact.type = type;
            exact.key = key;
            exact.allowed = permissive; // permissive: newly seen means allowed until switched off
            e.caps.add(exact);
        }
        exact.last = now;
        if (exact.allowed) {
            exact.uses++;
            save();
            return true;
        }
        exact.blocked++;
        save();
        return false;
    }

    public synchronized void log(int uid, String pkg, String action, String detail, String result) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                + " " + (pkg != null ? pkg : String.valueOf(uid)) + " " + action
                + (detail != null && !detail.isEmpty() ? " [" + detail + "]" : "") + " -> " + result;
        audit.addLast(line);
        while (audit.size() > AUDIT_MAX) audit.removeFirst();
        LOGGER.i(line);
    }

    public synchronized String dumpJson(boolean hostsActive, int hostsEntries) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("hostsActive", hostsActive);
        o.addProperty("hostsEntries", hostsEntries);
        o.add("entries", gson.toJsonTree(data.entries));
        o.addProperty("gatesEnabled", data.gatesEnabled);
        o.add("audit", gson.toJsonTree(new ArrayList<>(audit)));
        return gson.toJson(o);
    }

    /** Receives the current visibility list and queues it for asynchronous publication. */
    private volatile java.util.function.Consumer<String> visibleListener;

    public synchronized void onVisibleChanged(java.util.function.Consumer<String> listener) {
        visibleListener = listener;
        publishVisible();
    }

    /**
     * The apps that were authorized (Restricted or Permissive), as full Android UIDs, sorted and
     * comma separated: PlaitOS shows the moe.shizuku.privileged.api placeholder only to these, so apps that look for Shizuku's own
     * package (Aurora Store, ...) see it for the ones the user added and for nobody else.
     */
    synchronized String visibleUids() {
        java.util.TreeSet<Integer> ids = new java.util.TreeSet<>();
        for (Entry e : data.entries) if (e.allowed() && !e.shizukuHidden && stillSameApp(e)) ids.add(e.uid);
        StringBuilder sb = new StringBuilder();
        for (int id : ids) sb.append(sb.length() == 0 ? "" : ",").append(id);
        return sb.toString();
    }

    /**
     * Whether the entry's uid still belongs to the app it was made for. Android gives a freed uid to the next app
     * installed, and an entry is only re-checked when its app talks to the Broker ({@link #checkIdentity}), so a
     * reinstalled app's old entry would otherwise show the placeholder to whichever app got that uid next.
     */
    private static boolean stillSameApp(Entry e) {
        List<String> now = PackageManagerApis.getPackagesForUidNoThrow(e.uid);
        for (String pkg : e.packages) {
            if (!now.contains(pkg)) continue;
            if (e.cert == null) return true;
            String cert = Identity.signingCert(pkg, UserHandleCompat.getUserId(e.uid));
            return cert == null || cert.equals(e.cert); // unreadable: the package still matches, keep it
        }
        return false;
    }

    /** The manager's per-app "Show Shizuku" switch. False if the app has no entry. */
    public synchronized boolean setShizukuVisible(int uid, boolean visible) {
        Entry e = find(uid);
        if (e == null) return false;
        e.shizukuHidden = !visible;
        save();
        return true;
    }

    /** Publishes the list again (an app may have been installed, removed or reinstalled since). Unchanged lists are not rewritten. */
    public synchronized void republishVisible() {
        publishVisible();
    }

    private void publishVisible() {
        java.util.function.Consumer<String> l = visibleListener;
        if (l == null) return;
        l.accept(visibleUids());
    }

    private void save() {
        publishVisible();
        FileOutputStream out = null;
        try {
            out = atomicFile.startWrite();
            out.write(gson.toJson(data).getBytes());
            atomicFile.finishWrite(out);
        } catch (Throwable tr) {
            LOGGER.w(tr, "could not save policy");
            if (out != null) atomicFile.failWrite(out);
        }
    }
}
