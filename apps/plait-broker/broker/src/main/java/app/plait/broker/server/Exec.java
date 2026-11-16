package app.plait.broker.server;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Turns a Shizuku {@code newProcess} request into a command the policy can judge, and runs exactly what was judged.
 *
 * <p>Commands are matched as argument lists, never as a joined string. {@code Runtime.exec} starts the program directly,
 * so {@code ;}, {@code &&} or {@code |} inside an argument are just characters; chaining is only possible through programs
 * that interpret or start other programs. So:
 * <ul>
 *   <li>{@code sh -c SCRIPT} is accepted only when SCRIPT is one simple command (no operators, substitutions, globs or
 *       redirections). It is unwrapped and that command runs directly, without a shell. Any other use of a shell is refused.</li>
 *   <li>The program is looked up in a fixed system PATH and must live on a system partition; the client's PATH is ignored.</li>
 *   <li>The client's environment is reduced to a few harmless variables (no PATH, LD_*, CLASSPATH...).</li>
 *   <li>Launchers (programs that run other programs, e.g. {@code env}, {@code xargs}, {@code find -exec}) only match rules
 *       without wildcards.</li>
 * </ul>
 *
 * <p>Keys and rules share one syntax: shell-style quoting, one token per argument. In a rule, an unquoted {@code *} or
 * {@code ?} is a wildcard inside that one argument (never across a {@code /}), and a final unquoted {@code ...} matches
 * any remaining arguments.
 * A recorded command's key quotes every special character, so it only ever matches itself.
 */
final class Exec {
    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    interface Resolver {
        /** The absolute path to run for [argv0], or Refused. */
        String resolve(String argv0) throws Refused;
    }

    /** A request after unwrapping {@code sh -c} and resolving the program. */
    static final class Command {
        final List<String> argv;
        final String path;

        Command(List<String> argv, String path) {
            this.argv = argv;
            this.path = path;
        }

        String key() {
            return join(argv);
        }

        String[] execArgv() {
            String[] a = argv.toArray(new String[0]);
            a[0] = path;
            return a;
        }
    }

    /** Android's default PATH, in its order. */
    static final String[] SYSTEM_PATH = {"/product/bin", "/apex/com.android.runtime/bin", "/apex/com.android.art/bin",
            "/system_ext/bin", "/system/bin", "/system/xbin", "/odm/bin", "/vendor/bin", "/vendor/xbin"};
    private static final String[] SYSTEM_DIRS = {"/system/", "/system_ext/", "/product/", "/vendor/", "/odm/", "/apex/"};

    private static final Set<String> SHELLS = new HashSet<>(Arrays.asList("sh", "bash", "mksh", "ash", "dash", "zsh"));
    /** Programs that run other programs or code: only exact rules apply to them. */
    private static final Set<String> LAUNCHERS = new HashSet<>(Arrays.asList(
            "env", "xargs", "nice", "ionice", "timeout", "nohup", "setsid", "chroot", "runcon", "su", "sudo", "strace",
            "logwrapper", "taskset", "chrt", "time", "watch", "flock", "stdbuf", "script", "run-as", "unshare", "nsenter",
            "toybox", "busybox", "toolbox", "app_process", "app_process64", "app_process32", "dalvikvm", "dalvikvm64",
            "linker", "linker64", "awk", "sed", "perl", "python", "python3", "lua", "vi", "ed", "man", "less", "hush",
            "chpst", "envdir", "envuidgid", "setuidgid", "softlimit", "runsv", "runsvdir", "sv", "run-parts",
            "start-stop-daemon", "setpriv", "setarch", "linux32", "linux64", "cttyhack"));
    private static final Set<String> FIND_EXEC = new HashSet<>(Arrays.asList(
            "-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls"));
    private static final Set<String> ENV_ALLOWED = new HashSet<>(Arrays.asList("LANG", "TERM", "TZ", "COLUMNS", "LINES"));

    private Exec() {
    }

    // ------------------------------------------------------------------ requests

    static Command parse(String[] cmd, Resolver resolver) throws Refused {
        if (cmd == null || cmd.length == 0) throw new Refused("empty command");
        for (String a : cmd) {
            if (a == null) throw new Refused("null argument");
        }
        List<String> argv = new ArrayList<>(Arrays.asList(cmd));
        if (SHELLS.contains(base(argv.get(0)))) {
            if (argv.size() != 3 || !argv.get(1).equals("-c")) {
                throw new Refused("a shell is only accepted as: sh -c 'one simple command'");
            }
            argv = script(argv.get(2));
            if (SHELLS.contains(base(argv.get(0)))) throw new Refused("nested shell");
        }
        return new Command(argv, resolver.resolve(argv.get(0)));
    }

    /** The single simple command in a {@code sh -c} script, as an argument list. */
    static List<String> script(String script) throws Refused {
        List<Token> tokens = tokenize(script, false);
        if (tokens.isEmpty()) throw new Refused("empty script");
        List<String> argv = new ArrayList<>();
        for (Token t : tokens) argv.add(t.text.toString());
        return argv;
    }

    static boolean isLauncher(List<String> argv) {
        String b = base(argv.get(0));
        if (LAUNCHERS.contains(b) || SHELLS.contains(b)) return true;
        if (b.equals("find")) {
            for (String a : argv) {
                if (FIND_EXEC.contains(a)) return true;
            }
        }
        return false;
    }

    /** The environment for the child: the daemon's own, plus the harmless variables the client asked for. */
    static String[] environment(String[] requested) {
        if (requested == null) return null; // inherit the daemon's own
        Map<String, String> env = new TreeMap<>(System.getenv());
        for (String kv : requested) {
            int i = kv == null ? -1 : kv.indexOf('=');
            if (i <= 0) continue;
            String k = kv.substring(0, i);
            if (ENV_ALLOWED.contains(k) || k.startsWith("LC_")) env.put(k, kv.substring(i + 1));
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : env.entrySet()) out.add(e.getKey() + "=" + e.getValue());
        return out.toArray(new String[0]);
    }

    static String resolveOnDevice(String argv0) throws Refused {
        File found = null;
        if (argv0.contains("/")) {
            if (!argv0.startsWith("/")) throw new Refused("relative program path: " + argv0);
            found = new File(argv0);
        } else {
            for (String dir : SYSTEM_PATH) {
                File f = new File(dir, argv0);
                if (f.isFile() && f.canExecute()) {
                    found = f;
                    break;
                }
            }
            if (found == null) throw new Refused("not found in the system PATH: " + argv0);
        }
        String canonical;
        try {
            canonical = found.getCanonicalPath();
        } catch (IOException e) {
            throw new Refused("cannot resolve " + argv0);
        }
        for (String dir : SYSTEM_DIRS) {
            // Run the path as found, not the canonical one: multi-call binaries (toybox) dispatch on argv[0]'s name
            if (canonical.startsWith(dir)) return found.getPath();
        }
        throw new Refused("program outside the system partitions: " + canonical);
    }

    // ------------------------------------------------------------------ keys and rules

    /** The key for an argument list: shell-quoted so that it reads naturally and only ever matches itself. */
    static String join(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (String a : argv) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(quote(a));
        }
        return sb.toString();
    }

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_@%+=:,./-]+");

    static String quote(String a) {
        if (SAFE.matcher(a).matches() && !a.equals("...")) return a;
        return "'" + a.replace("'", "'\\''") + "'";
    }

    /** The argument list a key stands for (keys are literal by construction). */
    static List<String> split(String key) throws Refused {
        List<String> argv = new ArrayList<>();
        for (Token t : tokenize(key, true)) argv.add(t.text.toString());
        return argv;
    }

    /** A compiled rule. */
    static final class Rule {
        private final List<Pattern> args = new ArrayList<>();
        private boolean rest;
        private boolean literal = true;

        boolean matches(List<String> argv) {
            if (isLauncher(argv) && !literal) return false;
            if (rest ? argv.size() < args.size() : argv.size() != args.size()) return false;
            for (int i = 0; i < args.size(); i++) {
                if (!args.get(i).matcher(argv.get(i)).matches()) return false;
            }
            return true;
        }
    }

    static Rule rule(String text) throws Refused {
        List<Token> tokens = tokenize(text, true);
        if (tokens.isEmpty()) throw new Refused("empty rule");
        Rule r = new Rule();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.rest) {
                if (i != tokens.size() - 1 || i == 0) throw new Refused("'...' must be last and follow the program");
                r.rest = true;
                r.literal = false;
                continue;
            }
            if (i == 0 && t.glob) throw new Refused("the program name cannot be a wildcard");
            if (t.glob) r.literal = false;
            r.args.add(Pattern.compile(t.regex.toString(), Pattern.DOTALL));
        }
        return r;
    }

    // ------------------------------------------------------------------ tokenizer

    private static final class Token {
        final StringBuilder text = new StringBuilder();
        final StringBuilder regex = new StringBuilder();
        boolean glob;
        boolean rest;
        boolean quoted;

        void literal(char c) {
            text.append(c);
            regex.append(Pattern.quote(String.valueOf(c)));
        }
    }

    /**
     * Shell-style words. In script mode (a {@code sh -c} script) anything that would make the shell do more than run one
     * simple command is refused. In pattern mode (keys and rules) unquoted {@code * ?} are wildcards and {@code ...} is
     * "the rest"; shell operators are still refused, so a rule can never describe a chain.
     */
    private static List<Token> tokenize(String s, boolean pattern) throws Refused {
        List<Token> out = new ArrayList<>();
        Token cur = null;
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') {
                if (cur != null) out.add(finish(cur, pattern));
                cur = null;
                i++;
                continue;
            }
            if (c == '\n' || c == '\r') throw new Refused("more than one command (newline)");
            boolean start = cur == null;
            if (cur == null) cur = new Token();
            switch (c) {
                case '\'': {
                    int end = s.indexOf('\'', i + 1);
                    if (end < 0) throw new Refused("unterminated quote");
                    cur.quoted = true;
                    for (int k = i + 1; k < end; k++) cur.literal(s.charAt(k));
                    i = end + 1;
                    continue;
                }
                case '"': {
                    int k = i + 1;
                    cur.quoted = true;
                    while (true) {
                        if (k >= n) throw new Refused("unterminated quote");
                        char d = s.charAt(k);
                        if (d == '"') break;
                        if (d == '$' || d == '`') throw new Refused("substitution ($ or `)");
                        if (d == '\\' && k + 1 < n && "\"\\$`".indexOf(s.charAt(k + 1)) >= 0) {
                            cur.literal(s.charAt(k + 1));
                            k += 2;
                            continue;
                        }
                        if (d == '\\' && k + 1 < n && s.charAt(k + 1) == '\n') throw new Refused("line continuation");
                        cur.literal(d);
                        k++;
                    }
                    i = k + 1;
                    continue;
                }
                case '\\':
                    if (i + 1 >= n) throw new Refused("trailing backslash");
                    if (s.charAt(i + 1) == '\n') throw new Refused("line continuation");
                    cur.quoted = true;
                    cur.literal(s.charAt(i + 1));
                    i += 2;
                    continue;
                case ';': case '&': case '|': case '<': case '>': case '(': case ')':
                    throw new Refused("shell operator '" + c + "'");
                case '$': case '`':
                    throw new Refused("substitution ($ or `)");
                case '{': case '}':
                    throw new Refused("brace expansion");
                case '*': case '?':
                    if (!pattern) throw new Refused("glob '" + c + "'");
                    cur.text.append(c);
                    cur.regex.append(c == '*' ? "[^/]*" : "[^/]"); // never across '/': no escaping a directory
                    cur.glob = true;
                    i++;
                    continue;
                case '[':
                    throw new Refused("glob '['");
                case '~':
                case '#':
                    if (start) throw new Refused(c == '~' ? "home expansion" : "comment");
                    break;
                case '=':
                    if (!pattern && out.isEmpty()) throw new Refused("variable assignment");
                    break;
                default:
                    break;
            }
            cur.literal(c);
            i++;
        }
        if (cur != null) out.add(finish(cur, pattern));
        return out;
    }

    private static Token finish(Token t, boolean pattern) {
        // "..." unquoted, alone: the rest marker (a quoted '...' is a literal argument)
        if (pattern && !t.quoted && t.text.toString().equals("...")) t.rest = true;
        return t;
    }

    private static String base(String argv0) {
        int i = argv0.lastIndexOf('/');
        return i < 0 ? argv0 : argv0.substring(i + 1);
    }
}
