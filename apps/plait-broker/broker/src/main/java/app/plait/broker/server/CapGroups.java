package app.plait.broker.server;

import java.util.List;

/**
 * Which group a capability is listed under in the manager app, so related items collapse into one row:
 * commands by tool and, for file tools, by privileged or ordinary location (see {@link Tools}), Binder calls by interface
 * ({@code IPackageInstallerSession}). Other types are not grouped.
 */
public final class CapGroups {
    private CapGroups() {
    }

    public static String group(String type, String key) {
        if (Policy.CAP_EXEC.equals(type) || Policy.CAP_SYSTEM_WRITE.equals(type)) return commandGroup(key);
        if (Policy.CAP_BINDER.equals(type)) return binderGroup(key);
        if (Policy.CAP_SCOPED.equals(type)) return scopedGroup(key);
        if (Policy.CAP_GATE.equals(type)) return title(type, key);
        if (Policy.CAP_ASK.equals(type)) return askGroup(key);
        return key;
    }

    /** How a capability reads in the manager where its key is not self-explanatory (gates: "Inject taps, keys ..."). */
    public static String title(String type, String key) {
        if (Policy.CAP_GATE.equals(type)) {
            for (Classifier.Gate gate : Classifier.Gate.values()) {
                if (gate.id().equals(key)) {
                    return Character.toUpperCase(gate.description.charAt(0)) + gate.description.substring(1);
                }
            }
        }
        if (Policy.CAP_ASK.equals(type)) return askTitle(key);
        return key;
    }

    /** "settings put global foo" -> "Change the global setting foo"; "logcat" -> "Read the system log". */
    private static String askTitle(String key) {
        String[] w = key.split(" ");
        if (key.equals("logcat")) return "Read the system log";
        if (key.equals("su")) return "Use su (Plait's stand-in for root)";
        if (key.equals("hosts")) return "Replace the hosts file (DNS blocklist)";
        if (key.equals("private dns")) return "Change the DNS provider every app uses";
        if (key.equals("processes")) return "See which apps and processes are running";
        if (key.equals("connections")) return "See every app's network connections";
        if (key.equals("dmesg")) return "Read the kernel log";
        if (w.length == 4 && w[0].equals("settings") && w[1].equals("put")) return "Change the " + w[2] + " setting " + w[3];
        if (w.length == 2 && w[0].equals("dumpsys")) return "Read the state of " + w[1];
        if (w.length == 2 && w[0].equals("uninstall")) return "Uninstall " + w[1] + " (which it installed)";
        return key;
    }

    private static String askGroup(String key) {
        if (key.startsWith("settings put ")) return "Change settings";
        if (key.startsWith("dumpsys ")) return "Read system service state";
        return askTitle(key);
    }

    /**
     * Like commands share one toggle: by tool ("List files (ls)"), and for tools that read or change files, by where:
     * privileged locations only the shell can reach, or ordinary ones any app may read.
     */
    private static String commandGroup(String key) {
        List<String> argv = argv(key);
        if (argv == null) return key;
        Tools.Tool t = Tools.get(argv.get(0));
        String program = argv.get(0).substring(argv.get(0).lastIndexOf('/') + 1);
        if (t == null) return program;
        String group = t.title + " (" + t.name + ")";
        if (t.isFileTool()) group += Tools.privileged(argv) ? " · privileged locations" : " · ordinary locations";
        return group;
    }

    /** The section a command is listed in: its tool's category ("Files", "Network"...). */
    public static String category(String type, String key) {
        if (!Policy.CAP_EXEC.equals(type) && !Policy.CAP_SYSTEM_WRITE.equals(type)) return null;
        List<String> argv = argv(key);
        Tools.Tool t = argv != null ? Tools.get(argv.get(0)) : null;
        return t != null ? t.category.title : Tools.Category.OTHER.title;
    }

    /** Position of a category in the manager: the order of {@link Tools.Category}. */
    public static int categoryOrder(String title) {
        for (Tools.Category c : Tools.Category.values()) {
            if (c.title.equals(title)) return c.ordinal();
        }
        return Tools.Category.values().length;
    }

    /** What a command's options mean ("long format, all entries"), or "" . */
    public static String explain(String type, String key) {
        if (!Policy.CAP_EXEC.equals(type) && !Policy.CAP_SYSTEM_WRITE.equals(type)) return "";
        List<String> argv = argv(key);
        return argv != null ? Tools.explain(argv) : "";
    }

    /** The argument list of a command key, without a toybox/busybox prefix; null if it cannot be read. */
    private static List<String> argv(String key) {
        try {
            List<String> argv = Exec.split(key);
            if (argv.isEmpty()) return null;
            String p = argv.get(0).substring(argv.get(0).lastIndexOf('/') + 1);
            if ((p.equals("toybox") || p.equals("busybox")) && argv.size() > 1) argv = argv.subList(1, argv.size());
            return argv;
        } catch (Exec.Refused e) {
            return null;
        }
    }

    /** "freeze com.example" -> "Freeze, hide, suspend or disable parts of": one group per function, a row per target. */
    private static String scopedGroup(String key) {
        String id = key.contains(" ") ? key.substring(0, key.indexOf(' ')) : key;
        for (Classifier.Scope scope : Classifier.Scope.values()) {
            if (scope.id().equals(id)) {
                return Character.toUpperCase(scope.description.charAt(0)) + scope.description.substring(1);
            }
        }
        return id;
    }

    private static String binderGroup(String key) {
        // android.content.pm.IPackageInstaller.createSession -> IPackageInstaller; ...IFoo#12 -> IFoo
        String descriptor = key.contains("#") ? key.substring(0, key.lastIndexOf('#')) : key.substring(0, Math.max(key.lastIndexOf('.'), 0));
        return descriptor.substring(descriptor.lastIndexOf('.') + 1);
    }
}
