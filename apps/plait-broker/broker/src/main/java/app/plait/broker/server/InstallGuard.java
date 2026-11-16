package app.plait.broker.server;

import android.content.pm.PackageInstaller;
import android.os.Parcel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Package-installer calls made through the broker run as the shell user, which may do far more than an installer app:
 * grant every requested permission, downgrade, skip verification, install for all users, install APEXes, and name any
 * package (the Play Store, say) as the installer. So:
 * <ul>
 *   <li>{@code createSession} is rebuilt with those powers removed and the installer identity pinned to the calling app.
 *       An app may ask to be recorded as installed by another installer (some apps offer "install as Play Store"); that
 *       is allowed only after the user answers a warning dialog, and the answer is remembered ({@link Policy#CAP_INSTALLER});</li>
 *   <li>which packages an app may install is a capability of its own ({@link Policy#CAP_INSTALL});</li>
 *   <li>a session can only be opened or abandoned by the app that created it (every broker session belongs to the shell
 *       user, so without this any client could take over another client's install).</li>
 * </ul>
 */
final class InstallGuard {
    static final String INSTALLER = "android.content.pm.IPackageInstaller";
    static final String CREATE = INSTALLER + ".createSession";
    static final String OPEN = INSTALLER + ".openSession";
    static final String ABANDON = INSTALLER + ".abandonSession";

    /** installFlags that only the shell (or the system) may use; an installer app never gets them. Values: PackageManager. */
    private static final int[] SHELL_ONLY_FLAGS = {0x00000004, 0x00000040, 0x00000080, 0x00000100, 0x00010000, 0x00020000,
            0x00080000, 0x00100000, 0x00400000, 0x00800000, 0x01000000};
    private static final String[] SHELL_ONLY_NAMES = {"ALLOW_TEST", "ALL_USERS", "REQUEST_DOWNGRADE",
            "GRANT_ALL_REQUESTED_PERMISSIONS", "VIRTUAL_PRELOAD", "APEX", "DISABLE_VERIFICATION", "ALLOW_DOWNGRADE",
            "ALL_WHITELIST_RESTRICTED_PERMISSIONS", "DISABLE_ALLOWED_APEX_UPDATE_CHECK", "BYPASS_LOW_TARGET_SDK_BLOCK"};

    interface Asker {
        int ALLOW = 1, AS_ITSELF = 0, NO_ANSWER = -1;

        /** Asks the user whether [pkg] may install as [installer]; blocks until answered or timed out. */
        int askInstallerName(int uid, String pkg, String installer);
    }

    private final Policy policy;
    private final Asker asker;
    private final Map<Integer, Integer> owners = new HashMap<>(); // session id -> uid of the app that created it

    InstallGuard(Policy policy, Asker asker) {
        this.policy = policy;
        this.asker = asker;
    }

    /** The request to forward: [data] itself or a rebuilt parcel (then the caller recycles it). Throws to refuse. */
    Parcel before(int uid, String pkg, String key, Parcel data) {
        switch (key) {
            case CREATE:
                return createSession(uid, pkg, data);
            case OPEN:
            case ABANDON:
                requireOwner(uid, pkg, data);
                return data;
            default:
                return data;
        }
    }

    /** Remembers who created a session. */
    void after(int uid, String key, Parcel reply) {
        if (!CREATE.equals(key)) return;
        int pos = reply.dataPosition();
        try {
            reply.setDataPosition(0);
            reply.readException();
            int session = reply.readInt();
            synchronized (owners) {
                owners.put(session, uid);
            }
        } catch (Exception ignored) {
            // the call failed: no session was created
        } finally {
            reply.setDataPosition(pos);
        }
    }

    private Parcel createSession(int uid, String pkg, Parcel data) {
        // int createSession(in SessionParams params, String installerPackageName, String installerAttributionTag, int userId)
        data.setDataPosition(0);
        data.enforceInterface(INSTALLER);
        if (data.readInt() == 0) throw new SecurityException("createSession without parameters");
        PackageInstaller.SessionParams params = PackageInstaller.SessionParams.CREATOR.createFromParcel(data);
        String installerArg = data.readString();
        String attributionTag = data.readString();
        int userId = data.readInt();
        data.setDataPosition(0);

        int appUser = uid / 100000;
        if (userId != appUser) {
            policy.log(uid, pkg, "install", "user " + userId, "DENIED: another user");
            throw new SecurityException("Installing for user " + userId + " is not allowed for this app");
        }
        String target = (String) get(params, "appPackageName");
        String capKey = target != null ? target : Policy.ANY_PACKAGE;
        if (!policy.check(uid, pkg, Policy.CAP_INSTALL, capKey)) {
            policy.log(uid, pkg, "install", capKey, "DENIED");
            throw new SecurityException("Installing " + (target != null ? target : "undeclared packages") + " is not allowed for this app");
        }

        int flags = (int) get(params, "installFlags");
        List<String> removed = new ArrayList<>();
        for (int i = 0; i < SHELL_ONLY_FLAGS.length; i++) {
            if ((flags & SHELL_ONLY_FLAGS[i]) != 0) {
                flags &= ~SHELL_ONLY_FLAGS[i];
                removed.add(SHELL_ONLY_NAMES[i]);
            }
        }
        set(params, "installFlags", flags);
        // The app installs as itself unless the user agreed to the installer it names (the field wins over the argument)
        set(params, "installerPackageName", installerName(uid, pkg, params, installerArg));
        set(params, "originatingUid", uid);
        trySet(params, "whitelistedRestrictedPermissions", null);
        trySet(params, "forceQueryableOverride", false);
        policy.log(uid, pkg, "install", capKey + (removed.isEmpty() ? "" : " without " + String.join(",", removed)), "session allowed");

        Parcel out = Parcel.obtain();
        out.writeInterfaceToken(INSTALLER);
        out.writeInt(1);
        params.writeToParcel(out, 0);
        out.writeString(pkg);
        out.writeString(attributionTag);
        out.writeInt(userId);
        return out;
    }

    private String installerName(int uid, String pkg, Object params, String installerArg) {
        String requested = (String) get(params, "installerPackageName");
        if (requested == null) requested = installerArg;
        if (requested == null || requested.equals(pkg)) return pkg;
        Boolean allowed = policy.decision(uid, Policy.CAP_INSTALLER, requested);
        if (allowed == null) {
            policy.log(uid, pkg, "install", "as " + requested, "WARNING: asking the user");
            int answer = asker.askInstallerName(uid, pkg, requested);
            if (answer != Asker.NO_ANSWER) policy.setCap(uid, Policy.CAP_INSTALLER, requested, answer == Asker.ALLOW);
            allowed = answer == Asker.ALLOW;
        }
        policy.log(uid, pkg, "install", "as " + requested, allowed ? "allowed by the user" : "installs as itself instead");
        return allowed ? requested : pkg;
    }

    private void requireOwner(int uid, String pkg, Parcel data) {
        // openSession(int sessionId), abandonSession(int sessionId)
        data.setDataPosition(0);
        data.enforceInterface(INSTALLER);
        int session = data.readInt();
        data.setDataPosition(0);
        Integer owner;
        synchronized (owners) {
            owner = owners.get(session);
        }
        if (owner == null || owner != uid) {
            policy.log(uid, pkg, "install", "session " + session, "DENIED: created by another app");
            throw new SecurityException("Install session " + session + " was not created by this app");
        }
    }

    // SessionParams fields used here are public in the framework but not in the SDK
    private static Object get(Object o, String field) {
        try {
            return o.getClass().getField(field).get(o);
        } catch (ReflectiveOperationException e) {
            throw new SecurityException("cannot inspect install parameters (" + field + ")");
        }
    }

    /** For hardening-only fields: a framework without the field just does not get the extra adjustment. */
    private static void trySet(Object o, String field, Object value) {
        try {
            o.getClass().getField(field).set(o, value);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static void set(Object o, String field, Object value) {
        try {
            Field f = o.getClass().getField(field);
            f.set(o, value);
        } catch (ReflectiveOperationException e) {
            throw new SecurityException("cannot adjust install parameters (" + field + ")");
        }
    }
}
