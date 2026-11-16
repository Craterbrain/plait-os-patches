package app.plait.broker.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Named groups of capabilities for common jobs, so the user approves "App installer" instead of a dozen Binder methods.
 * A bundle is on when all its capabilities are; switching it sets them all. Shared by the daemon and the manager UI.
 */
public final class Bundles {
    public static final class Bundle {
        public final String title;
        public final String description;
        public final String type;
        public final List<String> keys;

        Bundle(String title, String description, String type, String... keys) {
            this.title = title;
            this.description = description;
            this.type = type;
            this.keys = Collections.unmodifiableList(Arrays.asList(keys));
        }
    }

    private static final String PI = "android.content.pm.IPackageInstaller.";
    private static final String PIS = "android.content.pm.IPackageInstallerSession.";

    public static final List<Bundle> ALL = Collections.unmodifiableList(Arrays.asList(
            new Bundle("App installer",
                    "Create, write and commit install sessions, as the app itself (no shell-only install flags). "
                            + "Which apps it may install is listed separately under Installs.",
                    Policy.CAP_BINDER,
                    "android.content.pm.IPackageManager.getPackageInstaller",
                    PI + "createSession", PI + "openSession", PI + "abandonSession", PI + "getSessionInfo",
                    PI + "getMySessions", PI + "updateSessionAppIcon", PI + "updateSessionAppLabel",
                    PIS + "setClientProgress", PIS + "addClientProgress", PIS + "getNames", PIS + "openWrite",
                    PIS + "write", PIS + "removeSplit", PIS + "close", PIS + "commit", PIS + "abandon"),
            new Bundle("Network information",
                    "Read the phone's addresses, routes, neighbours and traffic counters (ip, /proc/net). Read-only.",
                    Policy.CAP_EXEC,
                    "ip neigh", "ip neigh show ...", "ip -4 neigh show ...", "ip -6 neigh show ...",
                    "ip addr", "ip addr show ...", "ip -4 addr show ...", "ip -6 addr show ...",
                    "ip route", "ip route show ...", "ip -4 route show ...", "ip -6 route show ...",
                    "ip link", "ip link show ...",
                    "cat /proc/net/arp", "cat /proc/net/dev", "cat /proc/net/route"),
            new Bundle("Device information",
                    "Read build and hardware properties, battery, thermal and display state, and global/system settings "
                            + "(never the secure ones). Read-only.",
                    Policy.CAP_EXEC,
                    "getprop ro.build.*", "getprop ro.product.*", "getprop ro.hardware", "getprop ro.soc.*",
                    "dumpsys battery", "dumpsys thermalservice", "dumpsys display", "dumpsys uimode",
                    "settings get global *", "settings get system *",
                    "cat /proc/cpuinfo", "cat /proc/meminfo", "uname -a", "id")));

    private Bundles() {
    }
}
