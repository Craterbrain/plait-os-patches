package app.plait.broker.server;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Binder capability keys by method name ({@code android.content.pm.IPackageInstaller.createSession}) instead of transaction
 * code ({@code #1}). Codes are just the method's position in the .aidl file, so they shift between Android releases; a rule
 * keyed by code could silently start allowing a different method after an OS update.
 *
 * <p>Names come from the {@code TRANSACTION_<method>} constants that the AIDL compiler puts in every {@code Stub} class,
 * read from the framework the daemon runs on.
 */
final class BinderNames {
    private static final Map<String, Map<Integer, String>> CACHE = new HashMap<>();

    private BinderNames() {
    }

    /** The capability key for a call: {@code descriptor.method}, or {@code descriptor#code} if the method is unknown. */
    static String key(String descriptor, int code) {
        String name = method(descriptor, code);
        return name != null ? descriptor + "." + name : descriptor + "#" + code;
    }

    /** A key in the old {@code descriptor#code} form converted to a name, or unchanged if it cannot be resolved. */
    static String migrate(String key) {
        int i = key.lastIndexOf('#');
        if (i <= 0) return key;
        try {
            return key(key.substring(0, i), Integer.parseInt(key.substring(i + 1)));
        } catch (NumberFormatException e) {
            return key;
        }
    }

    static synchronized String method(String descriptor, int code) {
        Map<Integer, String> names = CACHE.get(descriptor);
        if (names == null) {
            names = load(descriptor);
            CACHE.put(descriptor, names);
        }
        return names.get(code);
    }

    private static Map<Integer, String> load(String descriptor) {
        Map<Integer, String> names = new HashMap<>();
        try {
            Class<?> stub = Class.forName(descriptor + "$Stub", false, BinderNames.class.getClassLoader());
            for (Field f : stub.getDeclaredFields()) {
                if (f.getType() != int.class || !Modifier.isStatic(f.getModifiers()) || !f.getName().startsWith("TRANSACTION_")) {
                    continue;
                }
                f.setAccessible(true);
                names.put(f.getInt(null), f.getName().substring("TRANSACTION_".length()));
            }
        } catch (Throwable ignored) {
            // Not a framework AIDL interface (or not on this classpath): keys fall back to codes
        }
        return names;
    }
}
