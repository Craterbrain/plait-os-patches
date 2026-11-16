package app.plait.testclient;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * The broker's checks as a list of tests that run without any screen: the activity shows them and the provider
 * ({@link DriveProvider}) runs them over adb. Every test goes through the same doors a real app would use: the Shizuku
 * API (newProcess) or the {@code su} client in its own process.
 *
 * <p>A test ends PASS, FAIL, or BLOCKED when it is waiting on you (a question was asked and not answered, or the broker
 * lists the thing but it is switched off). BLOCKED is not a failure.
 */
final class Suite {
    static final int PENDING = 0, RUNNING = 1, PASS = 2, FAIL = 3, BLOCKED = 4;
    static final String[] STATE = {"pending", "running", "PASS", "FAIL", "BLOCKED"};

    static final class Result {
        final int state;
        final String detail;

        Result(int state, String detail) {
            this.state = state;
            this.detail = detail;
        }
    }

    interface Check {
        Result run(Env env) throws Exception;
    }

    static final class Test {
        final String id, group, title, how;
        final Check check;
        volatile int state = PENDING;
        volatile String detail = "";
        volatile long millis;

        Test(String group, String id, String title, String how, Check check) {
            this.group = group;
            this.id = id;
            this.title = title;
            this.how = how;
            this.check = check;
        }
    }

    interface Listener {
        void changed(Test t);
    }

    /** What a test can do: run a command through the broker or through su, and read the answer. */
    static final class Res {
        final int exit;
        final String out, err;

        Res(int exit, String out, String err) {
            this.exit = exit;
            this.out = out;
            this.err = err;
        }

        String all() {
            return (out + (err.isEmpty() ? "" : "\n! " + err)).trim();
        }
    }

    static final class Env {
        final Context ctx;

        Env(Context ctx) {
            this.ctx = ctx;
        }

        boolean binder(long waitMillis) throws InterruptedException {
            long end = System.currentTimeMillis() + waitMillis;
            while (!Shizuku.pingBinder() && System.currentTimeMillis() < end) Thread.sleep(200);
            return Shizuku.pingBinder();
        }

        boolean granted() {
            try {
                return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0;
            } catch (Throwable t) {
                return false;
            }
        }

        /** A command through the broker's Shizuku door, the way apps start one. [stdin] may be null. */
        Res broker(String stdin, String... cmd) throws Exception {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, cmd, null, null);
            return finish(p, stdin);
        }

        /** The script through the su client, started as this app (not through the broker's door). */
        Res su(String script) throws Exception {
            String apk = ctx.getPackageManager().getApplicationInfo("app.plait.broker", 0).sourceDir;
            Process p = new ProcessBuilder("/system/bin/app_process", "-Djava.class.path=" + apk, "/system/bin",
                    "app.plait.broker.server.SuClient", "-c", script).start();
            return finish(p, null);
        }

        private Res finish(Process p, String stdin) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
            // stdin first: some broker commands (plait-overlay) only hand out their streams once they have read it
            if (stdin != null) p.getOutputStream().write(stdin.getBytes("UTF-8"));
            p.getOutputStream().close();
            Thread a = pump(p.getInputStream(), out), b = pump(p.getErrorStream(), err);
            // a question to you can take a minute; a command that never ends is cut off after two
            // (the remote process of the Shizuku API has no usable timed waitFor, so a timer cuts it off instead)
            boolean[] cut = {false};
            Thread timer = new Thread(() -> {
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(120));
                    cut[0] = true;
                    p.destroy();
                } catch (InterruptedException ignored) {
                }
            });
            timer.setDaemon(true);
            timer.start();
            int code = p.waitFor();
            timer.interrupt();
            if (cut[0]) return new Res(-1, out.toString("UTF-8").trim(), "timed out");
            a.join(2000);
            b.join(2000);
            return new Res(code, out.toString("UTF-8").trim(), err.toString("UTF-8").trim());
        }

        private static Thread pump(InputStream in, ByteArrayOutputStream to) {
            Thread t = new Thread(() -> {
                try (InputStream i = in) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = i.read(buf)) > 0) {
                        synchronized (to) {
                            to.write(buf, 0, r);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            t.start();
            return t;
        }
    }

    // ------------------------------------------------------------------ the tests

    static Result pass(String d) {
        return new Result(PASS, d);
    }

    static Result fail(String d) {
        return new Result(FAIL, d);
    }

    static Result blocked(String d) {
        return new Result(BLOCKED, d);
    }

    /** A refusal the broker gives as a failed command: exit 126 with its reason. */
    static boolean refused(Res r) {
        return r.exit == 126 && r.err.contains("plait-broker");
    }

    private static Result needsAccess(Env e) throws Exception {
        if (!e.binder(8000)) return fail("no broker connection");
        if (!e.granted()) return blocked("not allowed yet: use Request access");
        return null;
    }

    private static Result suGate(Res r) {
        if (r.err.contains("su was denied") || r.err.contains("no answer to the su question")) return blocked(r.err);
        if (r.err.contains("su is denied")) return blocked(r.err + " (switch it on at the app's page)");
        return null;
    }

    static List<Test> create() {
        List<Test> t = new ArrayList<>();
        String A = "Access", C = "Commands", R = "Refusals", S = "Fake root", O = "Overlays";

        t.add(new Test(A, "access.binder", "Connected to the broker", "Shizuku.pingBinder()", e -> e.binder(8000)
                ? pass("binder alive") : fail("no connection: is the daemon running?")));
        t.add(new Test(A, "access.granted", "Allowed to use it", "Shizuku.checkSelfPermission() == 0", e -> {
            Result r = needsAccess(e);
            return r != null ? r : pass("permission granted");
        }));
        t.add(new Test(A, "access.identity", "Server is the shell user", "Shizuku.getUid() == 2000", e -> {
            Result r = needsAccess(e);
            if (r != null) return r;
            return Shizuku.getUid() == 2000 ? pass("uid 2000") : fail("uid " + Shizuku.getUid());
        }));

        t.add(cmd(C, "cmd.id", "id", "id", 0, "uid=2000"));
        t.add(cmd(C, "cmd.ls", "List an ordinary directory", "ls /data/local/tmp", 0, null));
        t.add(cmd(C, "cmd.neigh", "Read the neighbor table", "ip neigh show", 0, null));
        t.add(cmd(C, "cmd.dns", "Read the Private DNS mode", "settings get global private_dns_mode", 0, null));

        t.add(new Test(R, "refuse.nested", "Nested shell is refused", "sh -c \"sh -c ls\"", e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, "sh", "-c", "sh -c ls");
            return refused(r) && r.err.contains("nested") ? pass(r.err) : fail(r.exit + " " + r.all());
        }));
        t.add(new Test(R, "refuse.reboot", "reboot is never allowed", "reboot", e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, "reboot");
            return refused(r) ? pass(r.err) : fail("exit " + r.exit + " " + r.all());
        }));
        t.add(new Test(R, "refuse.other_app", "Acting on another app needs its list entry",
                "am force-stop com.example.plait.notinstalled (harmless: no such app)", e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, "am", "force-stop", "com.example.plait.notinstalled");
            if (refused(r)) return pass("refused as designed: " + r.err);
            return r.exit == 0 ? pass("allowed (it is switched on for this app); nothing happened, there is no such app")
                    : fail("exit " + r.exit + " " + r.all());
        }));

        t.add(su(S, "su.id", "id answers as root", "su -c id", "id", 0, "uid=0(root)"));
        t.add(su(S, "su.echo", "echo", "su -c 'echo hi'", "echo hi", 0, "hi"));
        t.add(su(S, "su.passthrough", "A command goes through the broker", "su -c 'settings get global private_dns_mode'",
                "settings get global private_dns_mode", 0, null));
        t.add(su(S, "su.pipe", "Pipe between two checked commands", "pm list packages | head -n 3",
                "pm list packages | head -n 3", 0, "package:"));
        t.add(new Test(S, "su.pipe_lines", "A pipe really cuts the output", "pm list packages | head -n 3 gives 3 lines", e -> {
            Res r = e.su("pm list packages | head -n 3");
            Result g = suGate(r);
            if (g != null) return g;
            int lines = r.out.isEmpty() ? 0 : r.out.split("\n").length;
            return r.exit == 0 && lines == 3 ? pass("3 lines") : fail(lines + " lines, exit " + r.exit + " " + r.all());
        }));
        t.add(su(S, "su.vars", "Variables and command substitution", "A=5; B=$(echo x); echo $A$B", "A=5; B=$(echo x); echo $A$B",
                0, "5x"));
        t.add(su(S, "su.status", "$? is the last status", "false; echo $?", "false; echo $?", 0, "1"));
        t.add(su(S, "su.and_or", "&& and ||", "false && echo no || echo yes", "false && echo no || echo yes", 0, "yes"));
        t.add(su(S, "su.redirect_file", "Redirect to a file and read it back",
                "echo plait > /data/local/tmp/plait-su-test; cat < /data/local/tmp/plait-su-test; rm /data/local/tmp/plait-su-test",
                "echo plait > /data/local/tmp/plait-su-test; cat < /data/local/tmp/plait-su-test; rm /data/local/tmp/plait-su-test",
                0, "plait"));
        t.add(new Test(S, "su.redirect_system", "Writing under /system is not real", "echo x > /system/etc/plait-test", e -> {
            Res r = e.su("echo x > /system/etc/plait-test");
            Result g = suGate(r);
            if (g != null) return g;
            return pass("exit " + r.exit + (r.all().isEmpty() ? " (pretended: the system was not touched)" : " " + r.all()));
        }));
        t.add(new Test(S, "su.unsupported", "Wildcards are refused clearly", "ls /sdcard/*", e -> {
            Res r = e.su("ls /sdcard/*");
            Result g = suGate(r);
            if (g != null) return g;
            return r.exit == 2 && r.err.contains("not supported") ? pass(r.err) : fail("exit " + r.exit + " " + r.all());
        }));
        t.add(new Test(S, "su.refused", "A refused command stays refused", "su -c reboot", e -> {
            Res r = e.su("reboot");
            Result g = suGate(r);
            if (g != null) return g;
            return r.exit == 126 ? pass(r.err) : fail("exit " + r.exit + " " + r.all());
        }));

        t.add(new Test(O, "ovl.reject_config", "config_* values are refused", "bool/config_x on an app", e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker("bool/config_x true\n", "plait-overlay", "register", "plaitTestA", "com.example.plait.notinstalled");
            return r.exit != 0 && r.all().contains("config_") ? pass(r.err) : fail("exit " + r.exit + " " + r.all());
        }));
        t.add(new Test(O, "ovl.reject_framework", "The framework takes colors only", "bool/x on android", e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker("bool/x true\n", "plait-overlay", "register", "plaitTestB", "android");
            return r.exit != 0 && (r.all().contains("framework") || r.all().contains("only colors")) ? pass(r.err) : fail("exit " + r.exit + " " + r.all());
        }));
        t.add(new Test(O, "ovl.reject_string", "Strings and files are refused", "string/x hello", e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker("string/x hello\n", "plait-overlay", "register", "plaitTestC", "com.example.plait.notinstalled");
            return r.exit != 0 ? pass(r.err) : fail("exit " + r.exit + " " + r.all());
        }));
        t.add(new Test(O, "ovl.recolor", "Recolor an app (needs the switch on)",
                "plait-overlay register plaitTestProbe com.android.settings, then unregister", e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker("color/system_accent1_500 ffff0066\n", "plait-overlay", "register", "plaitTestProbe", "com.android.settings");
            if (r.exit != 0 && r.all().contains("may not")) {
                return blocked("listed but switched off: switch on 'change the look of com.android.settings' for this app");
            }
            if (r.exit != 0 && r.all().contains("commit failed")) {
                return blocked("Android refused the overlay: needs the PlaitOS framework patch or the overlay-helper module");
            }
            if (r.exit != 0) return fail("exit " + r.exit + " " + r.all());
            Res u = e.broker(null, "plait-overlay", "unregister", "plaitTestProbe");
            return u.exit == 0 ? pass("registered and removed") : fail("registered, but removing failed: " + u.all());
        }));

        // what the broker never runs, in any mode; every one is harmless if it did run (no such app, no-op arguments)
        String N = "Never list";
        t.add(never(N, "never.env", "env", "env", "id"));
        t.add(never(N, "never.nohup", "nohup", "nohup", "id"));
        t.add(never(N, "never.xargs", "xargs", "xargs", "echo"));
        t.add(never(N, "never.awk", "awk", "awk", "BEGIN{}"));
        t.add(never(N, "never.su", "su (the real one, not the fake-root client)", "su", "-c", "id"));
        t.add(never(N, "never.app_process", "app_process", "app_process", "/system/bin", "x.Y"));
        t.add(never(N, "never.run_as", "run-as another app", "run-as", "com.example.plait.notinstalled", "id"));
        t.add(never(N, "never.busybox", "busybox", "busybox", "id"));
        t.add(never(N, "never.timeout", "timeout", "timeout", "1", "id"));
        t.add(never(N, "never.find_exec", "find -exec", "find", "/data/local/tmp", "-maxdepth", "0", "-exec", "id", ";"));
        t.add(never(N, "never.instrument", "am instrument", "am", "instrument", "-w", "com.example.plait.notinstalled/x.Y"));
        t.add(never(N, "never.implicit_broadcast", "Implicit broadcast", "am", "broadcast", "-a", "plait.test.NOTHING"));
        t.add(never(N, "never.install_option", "Shell-only pm install option", "pm", "install", "-d", "/data/local/tmp/plait-none.apk"));

        // the parser: what is judged is exactly what runs, so anything a shell would expand is refused outright
        String P = "Parser";
        t.add(parse(P, "parse.semicolon", "Two commands with ;", "id; id", null));
        t.add(parse(P, "parse.pipe", "Pipe", "id | cat", null));
        t.add(parse(P, "parse.background", "Background &", "id &", null));
        t.add(parse(P, "parse.redirect", "Redirect >", "id > /data/local/tmp/plait-parse-test", null));
        t.add(parse(P, "parse.subst", "$(...)", "echo $(id)", "substitution"));
        t.add(parse(P, "parse.backtick", "Backticks", "echo `id`", "substitution"));
        t.add(parse(P, "parse.var", "$VAR", "echo $HOME", "substitution"));
        t.add(parse(P, "parse.glob", "Glob *", "ls /sdcard/*", "glob"));
        t.add(parse(P, "parse.brace", "Brace expansion", "echo {a,b}", "brace"));
        t.add(parse(P, "parse.tilde", "~", "ls ~", "home"));
        t.add(parse(P, "parse.comment", "# comment", "id # x", "comment"));
        t.add(parse(P, "parse.assign", "FOO=1 command", "FOO=1 id", "assignment"));
        t.add(parse(P, "parse.newline", "Newline", "id\nid", "newline"));
        t.add(new Test(P, "parse.relative", "Relative program path", "./id", e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, "./id");
            return refused(r) && r.err.contains("relative") ? pass(r.err) : fail("exit " + r.exit + " " + r.all());
        }));
        t.add(new Test(P, "parse.not_in_path", "Program not on the system PATH", "plait_no_such_tool", e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, "plait_no_such_tool");
            return refused(r) && r.err.contains("PATH") ? pass(r.err) : fail("exit " + r.exit + " " + r.all());
        }));

        // writes to the read-only system are answered by the broker: success when "System writes" is on, the honest
        // read-only error when it is off; either way the real system is never changed
        String W = "System writes";
        t.add(sysWrite(W, "sys.remount", "mount -o remount,rw /system", "mount -o remount,rw /system", null));
        t.add(sysWrite(W, "sys.touch", "touch under /system", "touch /system/etc/plait-test", "/system/etc/plait-test"));
        t.add(sysWrite(W, "sys.mkdir", "mkdir under /system", "mkdir /system/plait-test-dir", "/system/plait-test-dir"));
        t.add(sysWrite(W, "sys.cp", "cp into /system", "cp /system/etc/hosts /system/etc/plait-test-copy", "/system/etc/plait-test-copy"));
        t.add(sysWrite(W, "sys.rm", "rm under /system", "rm /system/etc/plait-no-such-file", null));
        t.add(sysWrite(W, "sys.chmod", "chmod under /system", "chmod 644 /system/etc/plait-no-such-file", null));
        t.add(new Test(W, "sys.mixed", "Mixed targets run for real and fail", "touch /system/etc/plait-test /data/local/tmp/plait-mixed", e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, "sh", "-c", "touch /system/etc/plait-test /data/local/tmp/plait-mixed");
            e.broker(null, "rm", "-f", "/data/local/tmp/plait-mixed");
            if (refused(r)) return blocked(r.err);
            return r.exit != 0 ? pass("failed on the read-only part: " + r.all()) : fail("pretended success for a mixed command");
        }));
        // Ask tier: a warning dialog on the phone, once per app and key. Allow them to see PASS; a "no" shows as BLOCKED.
        String K = "Ask";
        t.add(ask(K, "ask.logcat", "logcat asks first", "logcat -d -t 1"));
        t.add(ask(K, "ask.secure", "settings get secure asks first", "settings get secure accessibility_enabled"));
        t.add(ask(K, "ask.dumpsys", "dumpsys of everything asks first", "dumpsys -l"));
        t.add(new Test(K, "ask.once", "An answered question is not asked again", "logcat -d -t 1, twice", e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res first = e.broker(null, "sh", "-c", "logcat -d -t 1");
            if (refused(first)) return blocked(first.err);
            long t0 = System.currentTimeMillis();
            Res again = e.broker(null, "sh", "-c", "logcat -d -t 1");
            long ms = System.currentTimeMillis() - t0;
            if (refused(again)) return fail("second call refused: " + again.err);
            return ms < 3000 ? pass("second call took " + ms + " ms, no new question")
                    : fail("second call took " + ms + " ms: it probably asked again (an answer for 'just once' does this)");
        }));

        // Other apps, one function and one target at a time: refused and listed switched off until you switch it on.
        // The targets do not exist, so nothing happens even where the entry is already switched on.
        String T = "Other apps";
        String ghost = "com.example.plait.notinstalled";
        t.add(other(T, "other.disable", "Freezing an app", "pm", "disable-user", ghost));
        t.add(other(T, "other.clear", "Clearing an app's data", "pm", "clear", ghost));
        t.add(other(T, "other.grant", "Granting an app a permission", "pm", "grant", ghost, "android.permission.CAMERA"));
        t.add(other(T, "other.appops", "Changing an app's app-op", "appops", "set", ghost, "CAMERA", "allow"));
        t.add(new Test(T, "other.core", "A core app is refused (its entry cannot be switched on)", "am force-stop com.android.settings", e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, "am", "force-stop", "com.android.settings");
            return refused(r) ? pass(r.err) : fail("a core app was not refused: exit " + r.exit + " " + r.all());
        }));

        // Gated: with the global switch off (the default) every one is refused without a question
        String G = "Gated";
        t.add(gated(G, "gated.input", "input injection", "input", "keyevent", "0"));
        t.add(gated(G, "gated.screencap", "screen capture", "screencap", "-p", "/dev/null"));
        t.add(gated(G, "gated.dpm", "device admin", "dpm", "list-owners"));
        t.add(gated(G, "gated.bmgr", "backup manager", "bmgr", "enabled"));
        t.add(gated(G, "gated.service", "raw service call", "service", "call", "plait_none", "1"));
        t.add(gated(G, "gated.role", "system roles", "cmd", "role", "get-role-holders", "android.app.role.SMS"));
        return t;
    }

    /** A function that asks once per key; refused here means you said no (or did not answer). */
    private static Test ask(String group, String id, String title, String line) {
        return new Test(group, id, title, line, e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, "sh", "-c", line);
            if (refused(r)) return blocked(r.err);
            return r.exit == 0 ? pass("answered yes (or asked before): " + firstLine(r.out)) : fail("exit " + r.exit + " " + r.all());
        });
    }

    /** One function on another app: refused as "may not" until its entry is switched on, then it simply runs. */
    private static Test other(String group, String id, String title, String... cmd) {
        return new Test(group, id, title, String.join(" ", cmd), e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, cmd);
            if (refused(r) && r.err.contains("may not")) return pass(r.err);
            if (refused(r)) return fail("refused, but not as a list entry: " + r.err);
            return blocked("allowed: its entry is switched on for this app (nothing happened: no such app)");
        });
    }

    /** Refused without a question while the global Gated functions switch is off. */
    private static Test gated(String group, String id, String title, String... cmd) {
        return new Test(group, id, title, String.join(" ", cmd), e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, cmd);
            if (refused(r)) return pass(r.err);
            return blocked("not refused (exit " + r.exit + "): Gated functions look switched on; " + firstLine(r.all()));
        });
    }

    /** A call the broker must refuse (exit 126 with its reason) in every mode. */
    private static Test never(String group, String id, String title, String... cmd) {
        return new Test(group, id, title, String.join(" ", cmd), e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, cmd);
            return refused(r) ? pass(r.err) : fail("exit " + r.exit + " " + r.all());
        });
    }

    /** A script given as sh -c that is more than one simple command; [reason] is a word the refusal must contain. */
    private static Test parse(String group, String id, String title, String script, String reason) {
        return new Test(group, id, title, "sh -c '" + script + "'", e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, "sh", "-c", script.replace("\\n", "\n"));
            if (!refused(r)) return fail("exit " + r.exit + " " + r.all());
            return reason == null || r.err.contains(reason) ? pass(r.err) : fail("refused, but not for '" + reason + "': " + r.err);
        });
    }

    /** A write the broker pretends; [probe] is a path that must still not exist afterwards (null: nothing to probe). */
    private static Test sysWrite(String group, String id, String title, String script, String probe) {
        return new Test(group, id, title, script, e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, "sh", "-c", script);
            if (refused(r)) return blocked(r.err);
            if (r.exit != 0 && r.err.contains("Read-only file system")) {
                return blocked("System writes is switched off for this app (read-only error is the designed answer)");
            }
            if (r.exit != 0) return fail("exit " + r.exit + " " + r.all());
            if (probe != null) {
                Res p = e.broker(null, "ls", probe);
                if (p.exit == 0) return fail("the real system changed: " + probe + " exists");
            }
            return pass("pretended success; the system is unchanged");
        });
    }


    private static Test cmd(String group, String id, String title, String line, int exit, String contains) {
        return new Test(group, id, title, line, e -> {
            Result g = needsAccess(e);
            if (g != null) return g;
            Res r = e.broker(null, "sh", "-c", line);
            if (refused(r)) return blocked(r.err);
            boolean ok = r.exit == exit && (contains == null || r.out.contains(contains));
            return ok ? pass(firstLine(r.out)) : fail("exit " + r.exit + " " + r.all());
        });
    }

    private static Test su(String group, String id, String title, String how, String script, int exit, String contains) {
        return new Test(group, id, title, how, e -> {
            Res r = e.su(script);
            Result g = suGate(r);
            if (g != null) return g;
            boolean ok = r.exit == exit && (contains == null || r.out.contains(contains));
            return ok ? pass(firstLine(r.out)) : fail("exit " + r.exit + " " + r.all());
        });
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl);
        return line.isEmpty() ? "ok" : line;
    }

    // ------------------------------------------------------------------ running

    private final Context ctx;
    final List<Test> tests = create();
    private Listener listener;
    private volatile Thread worker;

    Suite(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean running() {
        Thread w = worker;
        return w != null && w.isAlive();
    }

    List<Test> select(String what) {
        List<Test> out = new ArrayList<>();
        for (String part : what.split(",")) {
            part = part.trim();
            for (Test t : tests) {
                if (part.equalsIgnoreCase("all") || t.id.equals(part) || t.group.equalsIgnoreCase(part)) {
                    if (!out.contains(t)) out.add(t);
                }
            }
        }
        return out;
    }

    /** Runs [which] one after the other in the background; false if a run is already going. */
    synchronized boolean start(List<Test> which) {
        if (running() || which.isEmpty()) return false;
        for (Test t : which) set(t, PENDING, "");
        Thread w = new Thread(() -> {
            Env env = new Env(ctx);
            for (Test t : which) {
                long t0 = System.currentTimeMillis();
                set(t, RUNNING, "");
                Result r;
                try {
                    r = t.check.run(env);
                } catch (Throwable x) {
                    Throwable c = x.getCause() != null ? x.getCause() : x;
                    r = fail(c.getClass().getSimpleName() + ": " + c.getMessage());
                }
                t.millis = System.currentTimeMillis() - t0;
                set(t, r.state, r.detail);
            }
            writeReport();
        }, "suite");
        worker = w;
        w.start();
        return true;
    }

    /** Waits for the current run, up to [seconds]. */
    void await(int seconds) {
        Thread w = worker;
        if (w == null) return;
        try {
            w.join(seconds * 1000L);
        } catch (InterruptedException ignored) {
        }
    }

    private void set(Test t, int state, String detail) {
        t.state = state;
        t.detail = detail;
        android.util.Log.i("PlaitTest", t.id + " " + STATE[state] + (detail.isEmpty() ? "" : " | " + detail.replace('\n', ' ')));
        Listener l = listener;
        if (l != null) l.changed(t);
    }

    String report() {
        Map<String, int[]> groups = new LinkedHashMap<>();
        StringBuilder b = new StringBuilder();
        int[] total = new int[5];
        for (Test t : tests) {
            total[t.state]++;
            groups.computeIfAbsent(t.group, k -> new int[5])[t.state]++;
            b.append(String.format("%-8s %-22s %s", STATE[t.state], t.id, t.title));
            if (!t.detail.isEmpty()) b.append("\n         ").append(t.detail.replace("\n", "\n         "));
            b.append('\n');
        }
        return "pass=" + total[PASS] + " fail=" + total[FAIL] + " blocked=" + total[BLOCKED] + " pending=" + total[PENDING]
                + " running=" + total[RUNNING] + "\n" + b;
    }

    private void writeReport() {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            try (FileWriter w = new FileWriter(new File(dir, "results.txt"))) {
                w.write(report());
            }
        } catch (Exception ignored) {
        }
    }

    boolean installedBroker() {
        try {
            ctx.getPackageManager().getApplicationInfo("app.plait.broker", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
