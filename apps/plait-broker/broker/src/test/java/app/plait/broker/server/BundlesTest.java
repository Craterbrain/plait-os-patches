package app.plait.broker.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class BundlesTest {
    private static boolean matches(String rule, String... argv) throws Exception {
        return Exec.rule(rule).matches(Arrays.asList(argv));
    }

    @Test
    public void everyCommandBundleKeyIsAValidRule() throws Exception {
        for (Bundles.Bundle b : Bundles.ALL) {
            if (!b.type.equals(Policy.CAP_EXEC)) continue;
            for (String key : b.keys) Exec.rule(key);
        }
    }

    @Test
    public void informationBundlesStayReadOnly() throws Exception {
        assertTrue(matches("ip neigh show ...", "ip", "neigh", "show", "dev", "wlan0"));
        assertTrue(matches("ip -4 addr show ...", "ip", "-4", "addr", "show", "wlan0"));
        assertFalse(matches("ip neigh show ...", "ip", "neigh", "flush", "all"));
        assertFalse(matches("ip addr show ...", "ip", "addr", "add", "10.0.0.1/24", "dev", "wlan0"));
        assertTrue(matches("settings get global *", "settings", "get", "global", "device_name"));
        assertFalse(matches("settings get global *", "settings", "get", "secure", "android_id"));
        assertFalse(matches("settings get global *", "settings", "put", "global", "x"));
        assertFalse(matches("getprop ro.build.*", "getprop", "persist.sys.foo"));
    }
}
