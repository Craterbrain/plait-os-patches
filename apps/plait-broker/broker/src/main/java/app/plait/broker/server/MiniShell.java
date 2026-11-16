package app.plait.broker.server;

import java.util.ArrayList;
import java.util.List;

/**
 * The small part of a shell that root apps rely on when they run {@code su -c "..."}: command lists joined by
 * {@code ;} {@code &&} {@code ||} {@code |} or newlines, quoting, variables ({@code $NAME ${NAME} $? $(command)}),
 * assignments, and redirections ({@code > >> < 2> 2>&1}).
 *
 * <p>This class only reads the text. It never runs anything: {@link SuServer} hands every command to the broker one at a
 * time, and a redirection to or from a file becomes a {@code tee} or {@code cat} the broker checks like any other.
 * What it cannot carry out (here-documents, subshells, background jobs, loops, wildcards) is reported as unsupported
 * instead of being run as something other than what was written.
 */
final class MiniShell {
    static final int BARE = 0, DOUBLE = 1, SINGLE = 2;

    /** A piece of a word and how it was quoted; BARE and DOUBLE text may hold expansions, SINGLE is literal. */
    static final class Part {
        final String text;
        final int mode;

        Part(String text, int mode) {
            this.text = text;
            this.mode = mode;
        }
    }

    static final class Word {
        final List<Part> parts = new ArrayList<>();
    }

    static final class Assign {
        final String name;
        final Word value;

        Assign(String name, Word value) {
            this.name = name;
            this.value = value;
        }
    }

    static final int OUT = 0, APPEND = 1, IN = 2, DUP_ERR_TO_OUT = 3;

    static final class Redirect {
        final int fd;
        final int kind;
        final Word target; // null for 2>&1

        Redirect(int fd, int kind, Word target) {
            this.fd = fd;
            this.kind = kind;
            this.target = target;
        }
    }

    /** One simple command and how it joins to the next. */
    static final class Step {
        final List<Assign> assigns = new ArrayList<>();
        final List<Word> words = new ArrayList<>();
        final List<Redirect> redirects = new ArrayList<>();
        String joinNext = ";"; // ; && || and | (this command's output is the next one's input)

        boolean isEmpty() {
            return assigns.isEmpty() && words.isEmpty() && redirects.isEmpty();
        }
    }

    /** What expansion needs from whoever runs the script. */
    interface Env {
        /** Value of a variable ("?" is the last status); empty when unset. */
        String var(String name);

        /** Output of a command substitution, with trailing newlines already removed. */
        String command(String script) throws Exception;
    }

    static final class Unsupported extends Exception {
        Unsupported(String m) {
            super(m);
        }
    }

    private MiniShell() {
    }

    // ------------------------------------------------------------------ reading

    static List<Step> parse(String script) throws Unsupported {
        return new Reader(script).read();
    }

    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) {
            this.s = s;
        }

        List<Step> read() throws Unsupported {
            List<Step> steps = new ArrayList<>();
            Step cur = new Step();
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r') {
                    i++;
                } else if (c == '#') {
                    while (i < n && s.charAt(i) != '\n') i++;
                } else if (c == '\n' || c == ';' || c == '&' || c == '|') {
                    String op = operator();
                    if (cur.isEmpty()) {
                        if (op.equals("\n") || op.equals(";")) continue; // blank line
                        throw new Unsupported("an operator with no command before it");
                    }
                    cur.joinNext = op.equals("\n") ? ";" : op;
                    steps.add(cur);
                    cur = new Step();
                } else if (c == '<' || c == '>' || (Character.isDigit(c) && isFdRedirect())) {
                    redirect(cur);
                } else if (c == '(' || c == ')' || c == '{' || c == '}') {
                    throw new Unsupported("subshells and groups");
                } else {
                    word(cur);
                }
            }
            if (!cur.isEmpty()) {
                cur.joinNext = ";";
                steps.add(cur);
            } else if (!steps.isEmpty() && steps.get(steps.size() - 1).joinNext.equals("|")) {
                throw new Unsupported("a pipe with no command after it");
            }
            return steps;
        }

        private String operator() throws Unsupported {
            char c = s.charAt(i++);
            if (c == '\n' || c == ';') return String.valueOf(c);
            boolean twice = i < s.length() && s.charAt(i) == c;
            if (twice) i++;
            if (c == '&') {
                if (!twice) throw new Unsupported("background jobs");
                return "&&";
            }
            return twice ? "||" : "|";
        }

        /** Digits followed directly by a redirection sign: the "2" of 2>/dev/null. */
        private boolean isFdRedirect() {
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
            return j > i && j - i <= 2 && j < s.length() && (s.charAt(j) == '>' || s.charAt(j) == '<');
        }

        private void redirect(Step st) throws Unsupported {
            int fd = -1;
            if (Character.isDigit(s.charAt(i))) {
                int j = i;
                while (Character.isDigit(s.charAt(j))) j++;
                fd = Integer.parseInt(s.substring(i, j));
                i = j;
            }
            char sign = s.charAt(i++);
            if (sign == '<') {
                if (i < s.length() && (s.charAt(i) == '<' || s.charAt(i) == '>')) throw new Unsupported("here-documents and read-write redirections");
                if (fd >= 0 && fd != 0) throw new Unsupported("this redirection");
                st.redirects.add(new Redirect(0, IN, target()));
                return;
            }
            int kind = OUT;
            if (i < s.length() && s.charAt(i) == '>') {
                kind = APPEND;
                i++;
            }
            if (fd < 0) fd = 1;
            if (fd != 1 && fd != 2) throw new Unsupported("this redirection");
            if (i < s.length() && s.charAt(i) == '&') {
                int j = i + 1;
                int k = j;
                while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
                String to = s.substring(j, k);
                if (fd == 2 && to.equals("1") && kind == OUT) {
                    st.redirects.add(new Redirect(2, DUP_ERR_TO_OUT, null));
                    i = k;
                    return;
                }
                throw new Unsupported("this redirection");
            }
            st.redirects.add(new Redirect(fd, kind, target()));
        }

        private Word target() throws Unsupported {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
            if (i >= s.length() || "\n;&|<>()".indexOf(s.charAt(i)) >= 0) throw new Unsupported("a redirection with no file");
            return readWord();
        }

        private void word(Step st) throws Unsupported {
            Word w = readWord();
            // NAME=value in front of the command words is an assignment
            if (st.words.isEmpty() && !w.parts.isEmpty() && w.parts.get(0).mode == BARE) {
                String first = w.parts.get(0).text;
                int eq = first.indexOf('=');
                if (eq > 0 && isName(first.substring(0, eq))) {
                    Word value = new Word();
                    if (eq + 1 < first.length()) value.parts.add(new Part(first.substring(eq + 1), BARE));
                    for (int k = 1; k < w.parts.size(); k++) value.parts.add(w.parts.get(k));
                    st.assigns.add(new Assign(first.substring(0, eq), value));
                    return;
                }
            }
            st.words.add(w);
        }

        /** One word, up to unquoted white space or an operator. */
        private Word readWord() throws Unsupported {
            Word w = new Word();
            StringBuilder bare = new StringBuilder();
            int n = s.length();
            boolean any = false;
            while (i < n) {
                char c = s.charAt(i);
                if (" \t\r\n;&|<>()".indexOf(c) >= 0) break;
                any = true;
                if (c == '\'') {
                    flush(w, bare);
                    int end = s.indexOf('\'', i + 1);
                    if (end < 0) throw new Unsupported("an unclosed quote");
                    w.parts.add(new Part(s.substring(i + 1, end), SINGLE));
                    i = end + 1;
                } else if (c == '"') {
                    flush(w, bare);
                    int end = closingQuote(i + 1);
                    w.parts.add(new Part(s.substring(i + 1, end), DOUBLE));
                    i = end + 1;
                } else if (c == '\\') {
                    if (i + 1 < n) {
                        flush(w, bare);
                        w.parts.add(new Part(String.valueOf(s.charAt(i + 1)), SINGLE));
                    }
                    i += 2;
                } else if (c == '$' && i + 1 < n && s.charAt(i + 1) == '(') {
                    int end = matchParen(i + 2);
                    bare.append(s, i, end + 1);
                    i = end + 1;
                } else if (c == '`') {
                    int end = s.indexOf('`', i + 1);
                    if (end < 0) throw new Unsupported("an unclosed backquote");
                    bare.append(s, i, end + 1);
                    i = end + 1;
                } else if (c == '*') {
                    // a wildcard would have to be expanded by listing directories on the app's behalf; not done
                    throw new Unsupported("wildcards (list the files with ls or find instead)");
                } else {
                    bare.append(c);
                    i++;
                }
            }
            flush(w, bare);
            if (!any) throw new Unsupported("an empty word");
            return w;
        }

        private static void flush(Word w, StringBuilder bare) {
            if (bare.length() > 0) {
                w.parts.add(new Part(bare.toString(), BARE));
                bare.setLength(0);
            }
        }

        /** Index of the closing double quote, skipping escapes and $( ... ). */
        private int closingQuote(int from) throws Unsupported {
            int j = from;
            while (j < s.length()) {
                char c = s.charAt(j);
                if (c == '\\') j += 2;
                else if (c == '$' && j + 1 < s.length() && s.charAt(j + 1) == '(') j = matchParen(j + 2) + 1;
                else if (c == '"') return j;
                else j++;
            }
            throw new Unsupported("an unclosed quote");
        }

        /** Index of the ")" that closes a "$(" whose contents start at [from]. */
        private int matchParen(int from) throws Unsupported {
            int depth = 1;
            int j = from;
            while (j < s.length()) {
                char c = s.charAt(j);
                if (c == '\'') {
                    int end = s.indexOf('\'', j + 1);
                    if (end < 0) break;
                    j = end + 1;
                } else if (c == '"') {
                    j = closingQuote(j + 1) + 1;
                } else if (c == '\\') {
                    j += 2;
                } else {
                    if (c == '(') depth++;
                    if (c == ')' && --depth == 0) return j;
                    j++;
                }
            }
            throw new Unsupported("an unclosed $(");
        }
    }

    static boolean isName(String s) {
        if (s.isEmpty() || !(Character.isLetter(s.charAt(0)) || s.charAt(0) == '_')) return false;
        for (int k = 1; k < s.length(); k++) {
            if (!(Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_')) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ expanding

    /** A stretch of expanded text: literal as written, or the value of an expansion (which unquoted is split on white space). */
    private static final class Seg {
        final String text;
        final boolean expanded;

        Seg(String text, boolean expanded) {
            this.text = text;
            this.expanded = expanded;
        }
    }

    /** The words of a command after variables and substitutions; unquoted results are split on white space. */
    static List<String> expand(List<Word> words, Env env) throws Exception {
        List<String> out = new ArrayList<>();
        for (Word w : words) expandWord(w, env, out);
        return out;
    }

    /** One word that must stay one word (a file name, an assignment's value). */
    static String expandOne(Word w, Env env) throws Exception {
        StringBuilder b = new StringBuilder();
        for (Part p : w.parts) {
            if (p.mode == SINGLE) b.append(p.text);
            else for (Seg g : scan(p.text, p.mode == DOUBLE, env)) b.append(g.text);
        }
        return b.toString();
    }

    private static void expandWord(Word w, Env env, List<String> out) throws Exception {
        StringBuilder cur = new StringBuilder();
        boolean started = false; // a quoted empty string still makes a word
        for (Part p : w.parts) {
            if (p.mode == SINGLE) {
                cur.append(p.text);
                started = true;
            } else if (p.mode == DOUBLE) {
                for (Seg g : scan(p.text, true, env)) cur.append(g.text);
                started = true;
            } else {
                for (Seg g : scan(p.text, false, env)) {
                    if (!g.expanded) {
                        cur.append(g.text);
                        started = true;
                        continue;
                    }
                    String v = g.text;
                    String trimmed = v.trim();
                    boolean lead = !v.isEmpty() && Character.isWhitespace(v.charAt(0));
                    boolean trail = !v.isEmpty() && Character.isWhitespace(v.charAt(v.length() - 1));
                    if (trimmed.isEmpty()) {
                        if (lead && (started || cur.length() > 0)) {
                            out.add(cur.toString());
                            cur.setLength(0);
                            started = false;
                        }
                        continue;
                    }
                    if (lead && (started || cur.length() > 0)) {
                        out.add(cur.toString());
                        cur.setLength(0);
                    }
                    String[] pieces = trimmed.split("\\s+");
                    for (int k = 0; k < pieces.length; k++) {
                        if (k > 0) {
                            out.add(cur.toString());
                            cur.setLength(0);
                        }
                        cur.append(pieces[k]);
                        started = true;
                    }
                    if (trail) {
                        out.add(cur.toString());
                        cur.setLength(0);
                        started = false;
                    }
                }
            }
        }
        if (started || cur.length() > 0) out.add(cur.toString());
    }

    /**
     * Cuts [raw] into literal and expanded stretches: $NAME, ${NAME}, $?, $(command) and `command`. In double quotes a
     * backslash escapes \" \\ \$ and \`.
     */
    private static List<Seg> scan(String raw, boolean inDouble, Env env) throws Exception {
        List<Seg> segs = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        int n = raw.length();
        int i = 0;
        while (i < n) {
            char c = raw.charAt(i);
            if (inDouble && c == '\\' && i + 1 < n && "\"\\$`".indexOf(raw.charAt(i + 1)) >= 0) {
                lit.append(raw.charAt(i + 1));
                i += 2;
                continue;
            }
            String value = null;
            if (c == '`') {
                int end = raw.indexOf('`', i + 1);
                value = env.command(raw.substring(i + 1, end));
                i = end + 1;
            } else if (c == '$' && i + 1 < n) {
                char d = raw.charAt(i + 1);
                if (d == '(') {
                    int end = matchParenIn(raw, i + 2);
                    value = env.command(raw.substring(i + 2, end));
                    i = end + 1;
                } else if (d == '{') {
                    int end = raw.indexOf('}', i + 2);
                    if (end < 0) throw new IllegalArgumentException("an unclosed ${");
                    String name = raw.substring(i + 2, end);
                    if (!isName(name) && !name.equals("?")) throw new IllegalArgumentException("only ${NAME} is supported");
                    value = env.var(name);
                    i = end + 1;
                } else if (d == '?') {
                    value = env.var("?");
                    i += 2;
                } else if (Character.isLetter(d) || d == '_') {
                    int j = i + 1;
                    while (j < n && (Character.isLetterOrDigit(raw.charAt(j)) || raw.charAt(j) == '_')) j++;
                    value = env.var(raw.substring(i + 1, j));
                    i = j;
                } else if (Character.isDigit(d) || d == '#' || d == '@' || d == '*' || d == '$' || d == '!') {
                    throw new IllegalArgumentException("positional and special parameters are not available in su -c");
                }
            }
            if (value == null) {
                lit.append(c);
                i++;
                continue;
            }
            if (lit.length() > 0) {
                segs.add(new Seg(lit.toString(), false));
                lit.setLength(0);
            }
            segs.add(new Seg(value, true));
        }
        if (lit.length() > 0) segs.add(new Seg(lit.toString(), false));
        return segs;
    }

    private static int matchParenIn(String raw, int from) {
        int depth = 1;
        int j = from;
        while (j < raw.length()) {
            char c = raw.charAt(j);
            if (c == '\'') {
                int end = raw.indexOf('\'', j + 1);
                j = end < 0 ? raw.length() : end + 1;
            } else if (c == '"') {
                j++;
                while (j < raw.length() && raw.charAt(j) != '"') j += raw.charAt(j) == '\\' ? 2 : 1;
                j++;
            } else if (c == '\\') {
                j += 2;
            } else {
                if (c == '(') depth++;
                if (c == ')' && --depth == 0) return j;
                j++;
            }
        }
        throw new IllegalArgumentException("an unclosed $(");
    }
}
