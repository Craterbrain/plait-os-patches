package app.plait.broker.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class MiniShellTest {
    private final Map<String, String> vars = new HashMap<>();
    private final MiniShell.Env env = new MiniShell.Env() {
        @Override
        public String var(String name) {
            return vars.getOrDefault(name, "");
        }

        @Override
        public String command(String script) {
            return script.equals("echo a b") ? "a b" : "out:" + script;
        }
    };

    private static List<MiniShell.Step> p(String s) throws Exception {
        return MiniShell.parse(s);
    }

    private List<String> argv(String script) throws Exception {
        return MiniShell.expand(p(script).get(0).words, env);
    }

    @Test
    public void splitsCommandLists() throws Exception {
        List<MiniShell.Step> s = p("pm list packages; settings get global x && echo \"a b\" || false\nid");
        assertEquals(5, s.size());
        assertEquals(Arrays.asList("echo", "a b"), MiniShell.expand(s.get(2).words, env));
        assertEquals("&&", s.get(1).joinNext);
        assertEquals("||", s.get(2).joinNext);
    }

    @Test
    public void joinsCommandsWithPipes() throws Exception {
        List<MiniShell.Step> s = p("pm list packages | grep -i foo | head -n 3; echo done");
        assertEquals(4, s.size());
        assertEquals("|", s.get(0).joinNext);
        assertEquals("|", s.get(1).joinNext);
        assertEquals(";", s.get(2).joinNext);
        assertEquals(Arrays.asList("grep", "-i", "foo"), MiniShell.expand(s.get(1).words, env));
        assertEquals("||", p("a || b").get(0).joinNext);
    }

    @Test
    public void readsRedirections() throws Exception {
        MiniShell.Step st = p("cmd arg > /data/local/tmp/out 2>&1 < /data/in").get(0);
        assertEquals(Arrays.asList("cmd", "arg"), MiniShell.expand(st.words, env));
        assertEquals(3, st.redirects.size());
        assertEquals(MiniShell.OUT, st.redirects.get(0).kind);
        assertEquals(1, st.redirects.get(0).fd);
        assertEquals(MiniShell.DUP_ERR_TO_OUT, st.redirects.get(1).kind);
        assertEquals(MiniShell.IN, st.redirects.get(2).kind);
        assertEquals(MiniShell.APPEND, p("echo x >> /f").get(0).redirects.get(0).kind);
        assertEquals(2, p("ls 2>/dev/null").get(0).redirects.get(0).fd);
        assertEquals("/f", MiniShell.expandOne(p("echo x >/f").get(0).redirects.get(0).target, env));
    }

    @Test
    public void expandsVariables() throws Exception {
        vars.put("A", "one");
        vars.put("LIST", "x y  z");
        vars.put("EMPTY", "");
        assertEquals(Arrays.asList("echo", "one", "one", "one-two"), argv("echo $A ${A} ${A}-two"));
        assertEquals(Arrays.asList("echo", "x", "y", "z"), argv("echo $LIST"));
        assertEquals(Arrays.asList("echo", "x y  z"), argv("echo \"$LIST\""));
        assertEquals(Arrays.asList("echo", "$A"), argv("echo '$A'"));
        assertEquals(Arrays.asList("echo", "pre-x", "y", "z-post"), argv("echo pre-$LIST-post"));
        assertEquals(Arrays.asList("echo", "b"), argv("echo $EMPTY b"));
        assertEquals(Arrays.asList("echo", "", "b"), argv("echo \"$EMPTY\" b"));
        assertEquals(Arrays.asList("echo", "$"), argv("echo $"));
        vars.put("?", "3");
        assertEquals(Arrays.asList("echo", "3"), argv("echo $?"));
    }

    @Test
    public void expandsCommandSubstitution() throws Exception {
        assertEquals(Arrays.asList("echo", "a", "b"), argv("echo $(echo a b)"));
        assertEquals(Arrays.asList("echo", "a b"), argv("echo \"$(echo a b)\""));
        assertEquals(Arrays.asList("x", "out:q"), argv("x `q`"));
        assertEquals(Arrays.asList("x", "out:a (b) c"), argv("x \"$(a (b) c)\""));
    }

    @Test
    public void readsAssignments() throws Exception {
        MiniShell.Step st = p("FOO=bar BAZ=\"a b\"").get(0);
        assertEquals(2, st.assigns.size());
        assertEquals("FOO", st.assigns.get(0).name);
        assertEquals("a b", MiniShell.expandOne(st.assigns.get(1).value, env));
        MiniShell.Step withCommand = p("FOO=1 echo hi").get(0);
        assertEquals(1, withCommand.assigns.size());
        assertEquals(Arrays.asList("echo", "hi"), MiniShell.expand(withCommand.words, env));
        // not an assignment once a command word is there, or when the name is not a name
        assertEquals(Arrays.asList("echo", "A=1"), argv("echo A=1"));
        assertEquals(Arrays.asList("1A=2"), argv("1A=2"));
    }

    @Test
    public void keepsExitStatusWord() throws Exception {
        vars.put("?", "0");
        assertEquals(Arrays.asList("echo", "__END__0"), argv("echo __END__$?"));
    }

    @Test
    public void escapesAndQuotes() throws Exception {
        vars.put("A", "v");
        assertEquals(Arrays.asList("echo", "a b", "$A", "\"q\"", "v"), argv("echo a\\ b \\$A \"\\\"q\\\"\" \"$A\""));
        assertEquals(Arrays.asList("echo", "it's"), argv("echo \"it's\""));
    }

    @Test
    public void refusesBrokenPipes() {
        for (String bad : new String[]{"| ls", "ls |", "ls | | cat"}) {
            assertThrows(bad, MiniShell.Unsupported.class, () -> MiniShell.parse(bad));
        }
    }

    @Test
    public void refusesWhatItCannotCarryOut() {
        for (String bad : new String[]{"cat <<EOF", "(ls)", "sleep 1 &", "echo 'open", "ls /sdcard/*", "echo >", "cmd 3>f", "cmd >&2", "x <> f"}) {
            assertThrows(bad, MiniShell.Unsupported.class, () -> MiniShell.parse(bad));
        }
    }

    @Test
    public void refusesSpecialParameters() {
        for (String bad : new String[]{"echo $1", "echo $@", "echo ${1}", "echo $$"}) {
            assertThrows(bad, IllegalArgumentException.class, () -> argv(bad));
        }
    }
}
