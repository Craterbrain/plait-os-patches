package app.plait.broker.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CapGroupsTest {
    private static void group(String type, String key, String expected) {
        assertEquals(key, expected, CapGroups.group(type, key));
    }

    @Test
    public void fileToolsSplitByPrivilegedAndOrdinaryLocations() {
        group(Policy.CAP_EXEC, "ls /data/local/tmp", "List files (ls) · privileged locations");
        group(Policy.CAP_EXEC, "ls", "List files (ls) · privileged locations");
        group(Policy.CAP_EXEC, "ls -la /sdcard/Download", "List files (ls) · ordinary locations");
        group(Policy.CAP_EXEC, "ls /system/bin", "List files (ls) · ordinary locations");
        group(Policy.CAP_EXEC, "cat /proc/net/arp", "Read files (cat) · privileged locations");
        group(Policy.CAP_EXEC, "cat /proc/cpuinfo", "Read files (cat) · ordinary locations");
        group(Policy.CAP_EXEC, "head -n 5 /system/build.prop", "Read the start of files (head) · ordinary locations");
        group(Policy.CAP_EXEC, "toybox ls /data/local/tmp", "List files (ls) · privileged locations");
    }

    @Test
    public void otherToolsGroupByTool() {
        group(Policy.CAP_EXEC, "ip neigh show", "Network (ip)");
        group(Policy.CAP_EXEC, "ip -4 addr show wlan0", "Network (ip)");
        group(Policy.CAP_EXEC, "pm list packages -3", "Packages (pm)");
        group(Policy.CAP_EXEC, "settings get secure android_id", "Settings (settings)");
    }

    @Test
    public void commandsAreListedByCategory() {
        assertEquals("Files", CapGroups.category(Policy.CAP_EXEC, "ls /sdcard"));
        assertEquals("Network", CapGroups.category(Policy.CAP_EXEC, "ping -c 1 1.1.1.1"));
        assertEquals("Other", CapGroups.category(Policy.CAP_EXEC, "someprogram"));
        assertEquals(null, CapGroups.category(Policy.CAP_BINDER, "x.Y.z"));
    }

    @Test
    public void optionsAreExplained() {
        assertEquals("all entries, long format", CapGroups.explain(Policy.CAP_EXEC, "ls -al /sdcard"));
        assertEquals("lines: 5", CapGroups.explain(Policy.CAP_EXEC, "head -n 5 /system/build.prop"));
        assertEquals("options the broker does not know: -Q", CapGroups.explain(Policy.CAP_EXEC, "ls -Q"));
    }

    @Test
    public void binderCallsGroupByInterface() {
        group(Policy.CAP_BINDER, "android.content.pm.IPackageInstaller.createSession", "IPackageInstaller");
        group(Policy.CAP_BINDER, "android.content.pm.IPackageInstallerSession.commit", "IPackageInstallerSession");
        group(Policy.CAP_BINDER, "android.content.pm.IPackageManager#136", "IPackageManager");
    }

    @Test
    public void scopedEntriesGroupByFunction() {
        group(Policy.CAP_SCOPED, "freeze com.google.android.youtube", "Freeze, hide, suspend or disable parts of");
        group(Policy.CAP_SCOPED, "permissions com.other", "Change permissions and app-ops of");
        group(Policy.CAP_SCOPED, "files com.other", "Read and change the shared-storage files of");
    }

    @Test
    public void gatesReadAsSentences() {
        assertEquals("Inject taps, keys and other input into any app", CapGroups.title(Policy.CAP_GATE, "input"));
        assertEquals("Capture or read what is on the screen", CapGroups.group(Policy.CAP_GATE, "screen"));
        assertEquals("unknown", CapGroups.title(Policy.CAP_GATE, "unknown"));
    }

    @Test
    public void askedAnswersReadAsSentences() {
        assertEquals("Read the system log", CapGroups.title(Policy.CAP_ASK, "logcat"));
        assertEquals("Change the global setting foo", CapGroups.title(Policy.CAP_ASK, "settings put global foo"));
        assertEquals("Change settings", CapGroups.group(Policy.CAP_ASK, "settings put secure bar"));
        assertEquals("Read system service state", CapGroups.group(Policy.CAP_ASK, "dumpsys meminfo"));
        assertEquals("Read the system log", CapGroups.group(Policy.CAP_ASK, "logcat"));
    }

    @Test
    public void otherTypesAreNotGrouped() {
        group(Policy.CAP_INSTALL, "app.morphe.*", "app.morphe.*");
    }
}
