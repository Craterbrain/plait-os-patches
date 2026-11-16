package app.plait.broker.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ExecTest {
    /** Resolves like the device would, without needing /system. */
    private static final Exec.Resolver FAKE = a -> a.startsWith("/") ? a : "/system/bin/" + a;

    private static Exec.Command parse(String... cmd) throws Exec.Refused {
        return Exec.parse(cmd, FAKE);
    }

    private static void assertRefused(String... cmd) {
        try {
            Exec.Command c = parse(cmd);
            fail("not refused: " + Arrays.toString(cmd) + " -> " + c.argv);
        } catch (Exec.Refused expected) {
        }
    }

    private static boolean matches(String rule, String... argv) throws Exec.Refused {
        return Exec.rule(rule).matches(Arrays.asList(argv));
    }

    @Test
    public void shellScriptsThatDoMoreThanOneCommandAreRefused() {
        String[] scripts = {"ls; reboot", "ls && reboot", "ls || reboot", "ls | sh", "ls & reboot", "ls $(id)", "ls `id`",
                "ls > /sdcard/x", "cat < /x", "echo ${X}", "echo $X", "ls *.txt", "ls a?", "ls [ab]", "FOO=1 ls", "ls\nreboot",
                "ls # c", "echo {a,b}", "ls ~", "(reboot)", "echo \"$HOME\"", "echo \"`id`\"", "ls 'x", "ls \\", "", "   ",
                "ls\\\nreboot", "sh -c id", "/system/bin/sh -c id"};
        for (String s : scripts) assertRefused("sh", "-c", s);
        assertRefused("bash", "-c", "id; id");
    }

    @Test
    public void otherUsesOfAShellAreRefused() {
        assertRefused("sh");
        assertRefused("sh", "/data/local/tmp/x.sh");
        assertRefused("sh", "-c", "id", "extra");
        assertRefused();
        assertRefused("ls", null);
    }

    @Test
    public void simpleScriptsAreUnwrappedAndRunWithoutAShell() throws Exec.Refused {
        assertEquals(List.of("ls", "/data/local/tmp"), parse("sh", "-c", "ls /data/local/tmp").argv);
        assertEquals(List.of("ip", "neigh", "show"), parse("sh", "-c", "  ip   neigh show ").argv);
        assertEquals(List.of("echo", "a b", "c;d", "e f"), parse("sh", "-c", "echo 'a b' \"c;d\" e\\ f").argv);
        assertEquals(List.of("echo", "it's"), parse("sh", "-c", "echo 'it'\\''s'").argv);
        assertEquals(List.of("echo", "$HOME", "a=b", "x#y"), parse("sh", "-c", "echo \"\\$HOME\" a=b x#y").argv);
        assertEquals(List.of("id"), parse("/system/bin/sh", "-c", "id").argv);
    }

    @Test
    public void withoutAShellOperatorsArePlainArguments() throws Exec.Refused {
        Exec.Command c = parse("ls", ";", "reboot");
        assertEquals(List.of("ls", ";", "reboot"), c.argv);
        assertEquals("ls ';' reboot", c.key());
        assertEquals(List.of("/system/bin/ls", ";", "reboot"), Arrays.asList(c.execArgv()));
    }

    @Test
    public void keysRoundTripAndOnlyMatchThemselves() throws Exec.Refused {
        List<String> odd = List.of("echo", "a b", "...", "it's", "*", "", "x=y", "$H", "~", "#");
        String key = Exec.join(odd);
        assertEquals(odd, Exec.split(key));
        assertTrue(Exec.rule(key).matches(odd));
        assertFalse(Exec.rule(key).matches(List.of("echo", "a b", "...", "it's", "anything", "", "x=y", "$H", "~", "#")));
        assertEquals("ip neigh show", Exec.join(List.of("ip", "neigh", "show")));
    }

    @Test
    public void wildcardsStayInsideOneArgument() throws Exec.Refused {
        assertTrue(matches("ip neigh show ...", "ip", "neigh", "show"));
        assertTrue(matches("ip neigh show ...", "ip", "neigh", "show", "dev", "wlan0"));
        assertFalse(matches("ip neigh show ...", "ip", "netns", "exec", "x", "sh"));
        assertFalse(matches("ip neigh show", "ip", "neigh", "show", "dev", "wlan0"));
        assertTrue(matches("cat /proc/net/*", "cat", "/proc/net/arp"));
        assertFalse(matches("cat /proc/net/*", "cat", "/proc/net/arp", "/data/x"));
        assertFalse(matches("cat /proc/net/*", "cat", "/proc/net/../../data/x"));
        assertFalse(matches("cat '/proc/net/*'", "cat", "/proc/net/arp"));
        assertTrue(matches("ls '...'", "ls", "..."));
        assertFalse(matches("ls '...'", "ls", "a", "b"));
    }

    @Test
    public void launchersOnlyMatchExactRules() throws Exec.Refused {
        assertFalse(matches("env ...", "env", "X=1", "sh"));
        assertFalse(matches("find /sdcard ...", "find", "/sdcard", "-exec", "sh", "{}", ";"));
        assertTrue(matches("find /sdcard ...", "find", "/sdcard", "-name", "x"));
        assertTrue(matches("toybox ls", "toybox", "ls"));
        assertFalse(matches("toybox *", "toybox", "reboot"));
    }

    @Test
    public void invalidRulesAreRejected() {
        for (String bad : new String[]{"* ...", "...", "ls ; reboot", "ls $(x)", "ip ... show"}) {
            try {
                Exec.rule(bad);
                fail("rule accepted: " + bad);
            } catch (Exec.Refused expected) {
            }
        }
    }

    @Test
    public void environmentKeepsOnlyHarmlessClientVariables() {
        String joined = String.join(" ", Exec.environment(new String[]{
                "PATH=/data/local/tmp", "LD_PRELOAD=/x.so", "TERM=xterm", "LC_ALL=C", "CLASSPATH=/x", "junk"}));
        assertFalse(joined.contains("LD_PRELOAD") || joined.contains("CLASSPATH") || joined.contains("PATH=/data/local/tmp"));
        assertTrue(joined.contains("TERM=xterm") && joined.contains("LC_ALL=C"));
    }
}
