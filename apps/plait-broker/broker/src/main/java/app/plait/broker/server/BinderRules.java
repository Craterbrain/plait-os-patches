package app.plait.broker.server;

import android.content.ComponentName;
import android.content.pm.VersionedPackage;
import android.os.Parcel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.plait.broker.server.Classifier.Decision;
import app.plait.broker.server.Classifier.Gate;
import app.plait.broker.server.Classifier.Scope;

/**
 * The {@link Classifier} tiers for Binder calls the broker forwards: {@code pm}, {@code am} and {@code appops} are only
 * front-ends to these services, so the same rules must hold here. On the sensitive interfaces, reads pass, the methods
 * below that act on one app are scoped to that app, the installer's own calls go to {@link InstallGuard}, and every
 * other method is gated: a method added in a new Android release cannot slip through unclassified.
 */
final class BinderRules {
    private interface TargetReader {
        /** The target packages, read from the call's arguments (data is positioned after the interface token). */
        List<String> read(Parcel data, Classifier.Apps apps);
    }

    private static final class Rule {
        final Scope scope;
        final TargetReader targets;

        Rule(Scope scope, TargetReader targets) {
            this.scope = scope;
            this.targets = targets;
        }
    }

    private static final TargetReader FIRST_STRING = (d, apps) -> Collections.singletonList(d.readString());
    private static final TargetReader FIRST_STRING_ARRAY = (d, apps) -> Arrays.asList(d.createStringArray());
    private static final TargetReader FIRST_COMPONENT = (d, apps) -> d.readInt() == 0
            ? Collections.emptyList()
            : Collections.singletonList(ComponentName.CREATOR.createFromParcel(d).getPackageName());
    private static final TargetReader FIRST_VERSIONED_PACKAGE = (d, apps) -> d.readInt() == 0
            ? Collections.emptyList()
            : Collections.singletonList(VersionedPackage.CREATOR.createFromParcel(d).getPackageName());

    private static final String PM = "android.content.pm.IPackageManager";
    private static final String PERMISSIONS = "android.permission.IPermissionManager";
    private static final String APP_OPS = "com.android.internal.app.IAppOpsService";
    private static final String AM = "android.app.IActivityManager";
    private static final String INSTALLER = "android.content.pm.IPackageInstaller";

    private static final Map<String, Rule> SCOPED = new HashMap<>();

    static {
        SCOPED.put(PM + ".setApplicationEnabledSetting", new Rule(Scope.FREEZE, FIRST_STRING));
        SCOPED.put(PM + ".setApplicationHiddenSettingAsUser", new Rule(Scope.FREEZE, FIRST_STRING));
        SCOPED.put(PM + ".setPackagesSuspendedAsUser", new Rule(Scope.FREEZE, FIRST_STRING_ARRAY));
        SCOPED.put(PM + ".setComponentEnabledSetting", new Rule(Scope.FREEZE, FIRST_COMPONENT));
        SCOPED.put(PM + ".clearApplicationUserData", new Rule(Scope.CLEAR, FIRST_STRING));
        SCOPED.put(PM + ".deletePackageAsUser", new Rule(Scope.UNINSTALL, FIRST_STRING));
        SCOPED.put(PM + ".deletePackageVersioned", new Rule(Scope.UNINSTALL, FIRST_VERSIONED_PACKAGE));
        SCOPED.put(INSTALLER + ".uninstall", new Rule(Scope.UNINSTALL, FIRST_VERSIONED_PACKAGE));
        SCOPED.put(PERMISSIONS + ".grantRuntimePermission", new Rule(Scope.PERMISSIONS, FIRST_STRING));
        SCOPED.put(PERMISSIONS + ".revokeRuntimePermission", new Rule(Scope.PERMISSIONS, FIRST_STRING));
        SCOPED.put(PERMISSIONS + ".updatePermissionFlags", new Rule(Scope.PERMISSIONS, FIRST_STRING));
        SCOPED.put(PERMISSIONS + ".addAllowlistedRestrictedPermission", new Rule(Scope.PERMISSIONS, FIRST_STRING));
        // setMode(int code, int uid, String packageName, int mode)
        SCOPED.put(APP_OPS + ".setMode", new Rule(Scope.PERMISSIONS, (d, apps) -> {
            d.readInt();
            d.readInt();
            return Collections.singletonList(d.readString());
        }));
        // setUidMode(int code, int uid, int mode)
        SCOPED.put(APP_OPS + ".setUidMode", new Rule(Scope.PERMISSIONS, (d, apps) -> {
            d.readInt();
            String pkg = apps.packageOfUid(d.readInt());
            return pkg != null ? Collections.singletonList(pkg) : Collections.emptyList();
        }));
        // resetAllModes(int reqUserId, String reqPackageName): null means every app
        SCOPED.put(APP_OPS + ".resetAllModes", new Rule(Scope.PERMISSIONS, (d, apps) -> {
            d.readInt();
            String pkg = d.readString();
            return pkg != null ? Collections.singletonList(pkg) : Collections.emptyList();
        }));
        SCOPED.put(AM + ".forceStopPackage", new Rule(Scope.STOP, FIRST_STRING));
        SCOPED.put(AM + ".killBackgroundProcesses", new Rule(Scope.STOP, FIRST_STRING));
        SCOPED.put(AM + ".clearApplicationUserData", new Rule(Scope.CLEAR, FIRST_STRING));
    }

    /** Interfaces where unclassified writes are gated, and the gate they fall under. */
    private static final Map<String, Gate> SENSITIVE = new HashMap<>();

    static {
        SENSITIVE.put(PM, Gate.SYSTEM);
        SENSITIVE.put(PERMISSIONS, Gate.SYSTEM);
        SENSITIVE.put(APP_OPS, Gate.SYSTEM);
        SENSITIVE.put(AM, Gate.SYSTEM);
        SENSITIVE.put("android.hardware.input.IInputManager", Gate.INPUT);
        SENSITIVE.put("android.view.IWindowManager", Gate.SYSTEM);
        SENSITIVE.put("android.app.admin.IDevicePolicyManager", Gate.SECURITY);
        SENSITIVE.put("android.app.role.IRoleManager", Gate.ROLES);
        SENSITIVE.put("android.app.INotificationManager", Gate.ROLES);
        SENSITIVE.put("android.view.accessibility.IAccessibilityManager", Gate.ROLES);
        SENSITIVE.put("com.android.internal.view.IInputMethodManager", Gate.ROLES);
        SENSITIVE.put("com.android.internal.telecom.ITelecomService", Gate.ROLES);
        SENSITIVE.put("android.app.backup.IBackupManager", Gate.BACKUP);
        SENSITIVE.put("android.os.IUserManager", Gate.USERS);
        SENSITIVE.put("android.content.IContentProvider", Gate.SECURITY); // includes the settings provider
    }

    /** Methods on sensitive interfaces that only read. */
    private static final List<String> READ_PREFIXES = Arrays.asList(
            "get", "is", "has", "query", "check", "list", "resolve", "can", "should");
    /** Methods that start activities, services or broadcasts as the shell: use am with an explicit component instead. */
    private static final List<String> START_PREFIXES = Arrays.asList(
            "startActivity", "startService", "startForegroundService", "broadcastIntent", "bindService",
            "startInstrumentation", "startNextMatchingActivity", "sendIntentSender");
    private static final Set<String> SCREEN_WORDS = Set.of("screenshot", "Screenshot", "captureDisplay", "capture");

    private BinderRules() {
    }

    /**
     * The tier of [key] (descriptor.method, see BinderNames) for [caller]. [data] is the forwarded call; its position is
     * restored. Scoped calls naming several apps must have every one of them allowed: [targets] receives them.
     */
    static Decision classify(String descriptor, String key, Parcel data, String caller, Classifier.Apps apps, List<String> targets) {
        Rule rule = SCOPED.get(key);
        if (rule != null) {
            List<String> pkgs;
            int pos = data.dataPosition();
            try {
                data.setDataPosition(0);
                data.enforceInterface(descriptor);
                pkgs = rule.targets.read(data, apps);
            } catch (RuntimeException e) {
                return Decision.never(key + ": arguments could not be read");
            } finally {
                data.setDataPosition(pos);
            }
            if (pkgs.isEmpty()) return Decision.gated(Gate.SYSTEM, key + " for every app");
            List<String> others = new ArrayList<>();
            for (String p : pkgs) {
                if (p == null) return Decision.never(key + ": no package named");
                if (!p.equals(caller)) others.add(p);
            }
            if (others.isEmpty()) return Decision.normal();
            if (rule.scope == Scope.UNINSTALL && others.size() == 1 && apps.installedBy(others.get(0), caller)) {
                return Decision.ask("uninstall " + others.get(0), "uninstall " + others.get(0) + ", which it installed");
            }
            targets.addAll(others);
            return Decision.scoped(rule.scope, others.get(0));
        }
        if (key.startsWith(INSTALLER + ".")) return Decision.normal(); // InstallGuard and the installer bundle
        Gate gate = SENSITIVE.get(descriptor);
        if (gate == null) return Decision.normal();
        String method = key.contains("#") ? "" : key.substring(descriptor.length() + 1);
        for (String p : READ_PREFIXES) {
            if (method.startsWith(p)) return Decision.normal();
        }
        for (String p : START_PREFIXES) {
            if (method.startsWith(p)) return Decision.never(key + " would act with the shell's identity; use am with an explicit component");
        }
        for (String w : SCREEN_WORDS) {
            if (method.contains(w)) return Decision.gated(Gate.SCREEN, key);
        }
        return Decision.gated(gate, key);
    }
}
