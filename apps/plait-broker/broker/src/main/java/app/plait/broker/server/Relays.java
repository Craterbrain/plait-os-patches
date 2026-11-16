package app.plait.broker.server;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;

import java.util.Set;
import java.util.regex.Pattern;

import rikka.hidden.compat.PackageManagerApis;

/**
 * Apps that pass privileged access on to other apps: Shizuku and its forks, Dhizuku, and anything else that offers a
 * broker API. Behind our broker, such an app would hand what the user approved for it to every app it serves, so it is
 * blocked outright: no consent dialog, no calls, and the user cannot switch it on.
 *
 * <p>A relay has to advertise itself to its own clients, and the Shizuku-style client libraries find a server by the
 * permission it defines; that permission is the reliable sign.
 */
final class Relays {
    private static final Set<String> KNOWN = Set.of("moe.shizuku.privileged.api", "com.rosan.dhizuku");
    /** moe.shizuku.manager.permission.API_V23, com.rosan.dhizuku.permission.API, forks with other prefixes. */
    private static final Pattern BROKER_API = Pattern.compile("(?i).*(shizuku|dhizuku).*\\.permission\\.API(_V\\d+)?");

    private Relays() {
    }

    /** Why [pkg] counts as a broker for other apps, or null. The broker's own manager app is never asked about. */
    static String reason(String pkg, int userId) {
        if (KNOWN.contains(pkg)) return "a known privilege broker";
        PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(pkg, PackageManager.GET_PERMISSIONS, userId);
        if (pi != null && pi.permissions != null) {
            for (PermissionInfo p : pi.permissions) {
                if (isBrokerApi(p.name)) return "offers a broker API to other apps (" + p.name + ")";
            }
        }
        return null;
    }

    static boolean isBrokerApi(String permission) {
        return permission != null && BROKER_API.matcher(permission).matches();
    }
}
