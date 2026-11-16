package app.plait.broker.server;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

import app.plait.broker.server.Classifier.Gate;
import app.plait.broker.server.Classifier.Scope;
import app.plait.broker.server.Classifier.Tier;

public class ClassifierTest {
    private static final String ME = "com.example.me";

    private static final Classifier.Apps APPS = new Classifier.Apps() {
        @Override
        public boolean installedBy(String pkg, String installer) {
            return pkg.equals("com.example.patched") && installer.equals(ME);
        }

        @Override
        public boolean sameSigner(String pkg, String other) {
            return pkg.equals("com.example.me.overlay") && other.equals(ME);
        }

        @Override
        public String packageOfUid(int uid) {
            return uid == 10123 ? "com.other" : uid == 10100 ? ME : null;
        }

        @Override
        public String providerOwner(String authority) {
            return authority.equals("com.other.files") ? "com.other" : authority.equals("com.example.me.data") ? ME : null;
        }
    };

    private static Classifier.Decision of(String... argv) {
        return Classifier.command(Arrays.asList(argv), ME, APPS);
    }

    private static void tier(Tier tier, String... argv) {
        assertEquals(String.join(" ", argv), tier, of(argv).tier);
    }

    private static void scoped(Scope scope, String target, String... argv) {
        Classifier.Decision d = of(argv);
        assertEquals(String.join(" ", argv), Tier.SCOPED, d.tier);
        assertEquals(scope, d.scope);
        assertEquals(target, d.target);
        assertEquals(scope.id() + " " + target, d.key);
    }

    private static void gated(Gate gate, String... argv) {
        Classifier.Decision d = of(argv);
        assertEquals(String.join(" ", argv), Tier.GATED, d.tier);
        assertEquals(gate, d.gate);
    }

    @Test
    public void actingOnItselfIsNormal() {
        tier(Tier.NORMAL, "pm", "grant", ME, "android.permission.WRITE_SECURE_SETTINGS");
        tier(Tier.NORMAL, "pm", "disable-user", "--user", "0", ME);
        tier(Tier.NORMAL, "cmd", "package", "clear", ME);
        tier(Tier.NORMAL, "appops", "set", ME, "SYSTEM_ALERT_WINDOW", "allow");
        tier(Tier.NORMAL, "appops", "set", "10100", "RUN_IN_BACKGROUND", "ignore");
        tier(Tier.NORMAL, "am", "force-stop", ME);
        tier(Tier.NORMAL, "am", "start", "-n", ME + "/.Main");
        tier(Tier.NORMAL, "content", "query", "--uri", "content://com.example.me.data/items");
        tier(Tier.NORMAL, "ls", "/sdcard/Android/data/" + ME + "/files");
    }

    @Test
    public void actingOnAnotherAppIsScopedToThatApp() {
        scoped(Scope.PERMISSIONS, "com.other", "pm", "grant", "com.other", "android.permission.CAMERA");
        scoped(Scope.PERMISSIONS, "com.other", "pm", "revoke", "com.other", "android.permission.CAMERA");
        scoped(Scope.PERMISSIONS, "com.other", "appops", "set", "com.other", "CAMERA", "ignore");
        scoped(Scope.PERMISSIONS, "com.other", "cmd", "appops", "set", "--uid", "10123", "CAMERA", "ignore");
        scoped(Scope.FREEZE, "com.other", "pm", "disable-user", "--user", "0", "com.other");
        scoped(Scope.FREEZE, "com.other", "pm", "disable", "com.other/.SomeReceiver");
        scoped(Scope.FREEZE, "com.other", "pm", "suspend", "com.other");
        scoped(Scope.FREEZE, "com.other", "pm", "install-existing", "com.other");
        scoped(Scope.CLEAR, "com.other", "pm", "clear", "com.other");
        scoped(Scope.UNINSTALL, "com.other", "pm", "uninstall", "--user", "0", "com.other");
        scoped(Scope.STOP, "com.other", "am", "force-stop", "com.other");
        scoped(Scope.STOP, "com.other", "cmd", "activity", "kill", "com.other");
        scoped(Scope.COMPONENTS, "com.other", "am", "startservice", "-n", "com.other/.Sync");
        scoped(Scope.PROVIDERS, "com.other", "content", "query", "--uri", "content://com.other.files/x");
        scoped(Scope.FILES, "com.other", "ls", "/sdcard/Android/data/com.other/files");
        scoped(Scope.FILES, "com.other", "rm", "-rf", "/storage/emulated/0/Android/obb/com.other");
        scoped(Scope.FILES, "com.other", "cat", "/storage/emulated/0/Android/data/" + ME + "/../com.other/secret");
    }

    @Test
    public void uninstallingWhatItInstalledIsAsked() {
        Classifier.Decision d = of("pm", "uninstall", "com.example.patched");
        assertEquals(Tier.ASK, d.tier);
        assertEquals("uninstall com.example.patched", d.key);
    }

    @Test
    public void deviceWideAndPrivacyItemsAreAsked() {
        tier(Tier.ASK, "logcat", "-d");
        tier(Tier.ASK, "dumpsys", "package");
        tier(Tier.ASK, "dumpsys");
        tier(Tier.ASK, "settings", "get", "secure", "android_id");
        tier(Tier.ASK, "settings", "put", "global", "window_animation_scale", "0.5");
        tier(Tier.ASK, "cmd", "overlay", "enable", "com.example.me.overlay");
        tier(Tier.NORMAL, "dumpsys", "battery");
        tier(Tier.NORMAL, "settings", "get", "global", "airplane_mode_on");
    }

    @Test
    public void powerfulFunctionsWithoutATargetAreGated() {
        gated(Gate.INPUT, "input", "tap", "100", "200");
        gated(Gate.INPUT, "cmd", "input", "keyevent", "3");
        gated(Gate.SCREEN, "screencap", "-p", "/sdcard/x.png");
        gated(Gate.SCREEN, "uiautomator", "dump");
        gated(Gate.ROLES, "cmd", "role", "add-role-holder", "android.app.role.SMS", "com.other");
        gated(Gate.ROLES, "cmd", "notification", "allow_listener", "com.other/.L");
        gated(Gate.ROLES, "ime", "set", "com.other/.Ime");
        gated(Gate.ROLES, "pm", "set-home-activity", "com.other");
        gated(Gate.SECURITY, "settings", "put", "secure", "enabled_accessibility_services", "com.other/.A");
        gated(Gate.SECURITY, "settings", "put", "global", "adb_enabled", "1");
        gated(Gate.SECURITY, "settings", "put", "global", "package_verifier_enable", "0");
        gated(Gate.SECURITY, "dpm", "set-active-admin", "com.other/.Admin");
        gated(Gate.SECURITY, "content", "call", "--uri", "content://settings/secure", "--method", "PUT_secure");
        gated(Gate.BACKUP, "bmgr", "backupnow", "com.other");
        gated(Gate.BINDER, "service", "call", "package", "5");
        gated(Gate.USERS, "pm", "create-user", "x");
        gated(Gate.SYSTEM, "pm", "set-installer", "com.other", "com.android.vending");
        gated(Gate.SYSTEM, "cmd", "overlay", "enable", "com.other.overlay");
    }

    @Test
    public void hiddenExecutionIsNever() {
        tier(Tier.NEVER, "env", "input", "tap", "1", "1");
        tier(Tier.NEVER, "nohup", "id");
        tier(Tier.NEVER, "app_process", "/system/bin", "com.example.Main");
        tier(Tier.NEVER, "run-as", "com.other", "ls");
        tier(Tier.NEVER, "find", "/sdcard", "-exec", "rm", "{}", ";");
        tier(Tier.NEVER, "am", "instrument", "com.other/.Runner");
        tier(Tier.NEVER, "am", "broadcast", "-a", "android.intent.action.BOOT_COMPLETED");
        tier(Tier.NEVER, "pm", "install", "-g", "/sdcard/a.apk");
        tier(Tier.NEVER, "content", "query", "--uri", "content://nobody.owns.this");
    }

    @Test
    public void toyboxAppletsAreClassifiedAsThemselves() {
        gated(Gate.INPUT, "toybox", "input", "tap", "1", "1");
        scoped(Scope.FILES, "com.other", "toybox", "ls", "/sdcard/Android/data/com.other");
    }

    @Test
    public void installsAndOrdinaryCommands() {
        tier(Tier.INSTALL, "pm", "install", "-r", "/data/local/tmp/a.apk");
        tier(Tier.NORMAL, "ip", "neigh", "show");
        tier(Tier.NORMAL, "pm", "list", "packages", "-3");
        tier(Tier.NORMAL, "cmd", "wifi", "status");
        tier(Tier.NORMAL, "wm", "density");
    }
}
