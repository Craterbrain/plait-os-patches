package app.plait.broker.server;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import moe.shizuku.server.IRemoteProcess;

/**
 * "Fake root": the answer to {@code su} for apps that only want to run commands as root. The {@code su} client
 * ({@link SuClient}) runs in the app, so the kernel tells this server which app is asking (SO_PEERCRED). Each command it
 * sends is run through the broker exactly as if the app had run it itself: same allow list, same dialogs, same refusals.
 * Nothing here is root. The server does not hand out more than the broker would; it only speaks the language root apps
 * expect (uid 0 for {@code id}, {@code su -c "a | b > file"}, variables, exit codes).
 *
 * <p>A redirection to a file is a {@code tee} the broker checks like the app's own {@code tee}; reading from a file is a
 * {@code cat}. So writing to a protected path asks, is pretended, or is refused exactly as {@code tee path} would be.
 *
 * <p>Wire format. The client sends {@code "SU1 c\n"} followed by the script and then end-of-input ({@code su -c}), or
 * {@code "SU1 i\n"} followed by lines as they come (interactive, how libsu talks). The server replies with frames:
 * one byte (1 = stdout, 2 = stderr, 3 = exit code) and a 4-byte big-endian length, then the bytes.
 */
final class SuServer {
    static final String PATH = "/data/local/tmp/plait/su.sock";
    static final String FAKE_ID = "uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0";
    private static final int EXIT = Integer.MIN_VALUE;
    private static final int MAX_SUBSTITUTION_DEPTH = 4;
    private static final int MAX_CAPTURE = 1 << 20;
    private static final int MAX_CLIENTS = 16;

    private final BrokerService broker;

    SuServer(BrokerService broker) {
        this.broker = broker;
    }

    void start() {
        Thread t = new Thread(this::serve, "su-server");
        t.setDaemon(true);
        t.start();
    }

    private void serve() {
        try {
            File sock = new File(PATH);
            sock.getParentFile().mkdirs();
            sock.getParentFile().setExecutable(true, false);
            sock.delete();
            LocalSocket server = new LocalSocket();
            server.bind(new LocalSocketAddress(PATH, LocalSocketAddress.Namespace.FILESYSTEM));
            sock.setReadable(true, false);
            sock.setWritable(true, false);
            LocalServerSocket ss = new LocalServerSocket(server.getFileDescriptor());
            // A few connections at a time: each holds a thread, and any app can connect
            java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(MAX_CLIENTS);
            while (true) {
                LocalSocket client = ss.accept();
                if (!slots.tryAcquire()) {
                    try {
                        client.close();
                    } catch (IOException ignored) {
                    }
                    continue;
                }
                new Thread(() -> {
                    try {
                        handle(client);
                    } finally {
                        slots.release();
                    }
                }, "su-client").start();
            }
        } catch (IOException e) {
            android.util.Log.w("PlaitBroker", "su server stopped", e);
        }
    }

    private void handle(LocalSocket client) {
        try (LocalSocket s = client) {
            Credentials who = s.getPeerCredentials();
            Out out = new Out(new DataOutputStream(s.getOutputStream()));
            InputStream in = s.getInputStream();
            String header = readLine(in);
            if (header == null || !header.startsWith("SU1 ")) return;
            Session session = new Session(who.getUid());
            if (header.equals("SU1 i")) {
                String line;
                while ((line = readLine(in)) != null) {
                    if (line.trim().isEmpty()) continue;
                    if (session.exec(line, out) == EXIT) break;
                }
                out.exit(0);
            } else {
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) > 0) all.write(buf, 0, r);
                int status = session.exec(all.toString("UTF-8"), out);
                out.exit(status == EXIT ? session.status : status);
            }
        } catch (Exception e) {
            android.util.Log.w("PlaitBroker", "su client failed", e);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') return b.toString("UTF-8");
            b.write(c);
        }
        return b.size() == 0 ? null : b.toString("UTF-8");
    }

    // ------------------------------------------------------------------ one connection

    /** The shell state of one {@code su} connection: variables and the last status. */
    private final class Session implements MiniShell.Env {
        final int uid;
        final Map<String, String> vars = new HashMap<>();
        int status;
        private int depth;

        Session(int uid) {
            this.uid = uid;
            vars.put("PATH", "/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin");
            vars.put("HOME", "/");
            vars.put("USER", "root");
        }

        @Override
        public String var(String name) {
            if (name.equals("?")) return String.valueOf(status);
            String v = vars.get(name);
            return v == null ? "" : v;
        }

        @Override
        public String command(String script) throws Exception {
            if (depth >= MAX_SUBSTITUTION_DEPTH) throw new IllegalArgumentException("command substitutions nested too deeply");
            depth++;
            try {
                Capture cap = new Capture(realOut);
                int saved = status;
                exec(script, cap);
                status = saved;
                String text = cap.text();
                int end = text.length();
                while (end > 0 && text.charAt(end - 1) == '\n') end--;
                return text.substring(0, end);
            } finally {
                depth--;
            }
        }

        private Out realOut; // where the connection's output goes (substitutions pass their errors there)

        /** Runs a script; returns the status of the last command, or EXIT. */
        int exec(String script, Out out) throws IOException {
            if (realOut == null) realOut = out;
            List<MiniShell.Step> steps;
            try {
                steps = MiniShell.parse(script);
            } catch (MiniShell.Unsupported u) {
                out.text(2, "su: not supported by Plait's fake root: " + u.getMessage() + "\n");
                return status = 2;
            }
            String join = ";";
            int i = 0;
            while (i < steps.size()) {
                int end = i;
                while (steps.get(end).joinNext.equals("|") && end + 1 < steps.size()) end++;
                List<MiniShell.Step> pipeline = steps.subList(i, end + 1);
                i = end + 1;
                boolean skip = (join.equals("&&") && status != 0) || (join.equals("||") && status == 0);
                join = pipeline.get(pipeline.size() - 1).joinNext;
                if (skip) continue;
                int r;
                try {
                    r = pipeline(pipeline, out);
                } catch (IllegalArgumentException e) {
                    out.text(2, "su: " + e.getMessage() + "\n");
                    r = 2;
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    out.text(2, "su: " + e.getMessage() + "\n");
                    r = 1;
                }
                if (r == EXIT) return EXIT;
                status = r;
            }
            return status;
        }

        // -------------------------------------------------------------- resolving a command

        private final class Target {
            static final int PIPE = 0, DROP = 1, FILE = 2;
            final int kind;
            final String path;
            final boolean append;

            Target(int kind, String path, boolean append) {
                this.kind = kind;
                this.path = path;
                this.append = append;
            }
        }

        /** A command with its words expanded and its redirections worked out in order, as sh does. */
        private final class Resolved {
            List<String> argv;
            Target out, err;
            String inFile;
        }

        private Resolved resolve(MiniShell.Step st) throws Exception {
            Resolved r = new Resolved();
            r.argv = MiniShell.expand(st.words, this);
            if (r.argv.isEmpty()) {
                // only assignments: they stay. In front of a command they would only apply to that command's own
                // environment, which the broker restricts anyway, so they are not applied then.
                for (MiniShell.Assign a : st.assigns) vars.put(a.name, MiniShell.expandOne(a.value, this));
            }
            Target outPipe = new Target(Target.PIPE, null, false), errPipe = new Target(Target.PIPE, null, false);
            Target[] fd = {null, outPipe, errPipe};
            for (MiniShell.Redirect red : st.redirects) {
                switch (red.kind) {
                    case MiniShell.DUP_ERR_TO_OUT:
                        fd[2] = fd[1];
                        break;
                    case MiniShell.IN: {
                        String path = fileName(red);
                        r.inFile = path.equals("/dev/null") ? null : path;
                        break;
                    }
                    default: {
                        String path = fileName(red);
                        fd[red.fd] = path.equals("/dev/null") ? new Target(Target.DROP, null, false)
                                : new Target(Target.FILE, path, red.kind == MiniShell.APPEND);
                    }
                }
            }
            r.out = fd[1];
            r.err = fd[2];
            return r;
        }

        private String fileName(MiniShell.Redirect red) throws Exception {
            List<String> names = MiniShell.expand(java.util.Collections.singletonList(red.target), this);
            if (names.size() != 1 || names.get(0).isEmpty()) throw new IllegalArgumentException("ambiguous redirect");
            String path = names.get(0);
            if (!path.startsWith("/")) throw new IllegalArgumentException("redirect to a relative path: " + path + " (use a full path)");
            return path;
        }

        // -------------------------------------------------------------- running commands

        private final class Stage {
            InputStream stdout = new ByteArrayInputStream(new byte[0]);
            InputStream stderr = new ByteArrayInputStream(new byte[0]);
            OutputStream stdin = OutputStream.nullOutputStream();
            IRemoteProcess process; // null for a builtin
            int status;

            int finish() {
                if (process == null) return status;
                try {
                    return process.waitFor();
                } catch (Exception e) {
                    return 1;
                }
            }

            /** True when it has already ended with an error: a refusal, which the broker answers at once. */
            boolean failedFast() {
                if (process == null) return false;
                try {
                    return !process.alive() && process.exitValue() != 0;
                } catch (Exception e) {
                    return false;
                }
            }

            void destroy() {
                if (process != null) {
                    try {
                        process.destroy();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        private final class Node {
            Resolved r;
            Stage in, main, sink, errSink;
        }

        private Stage broker(String... argv) {
            Stage g = new Stage();
            IRemoteProcess p = SuServer.this.broker.runAs(uid, argv);
            g.process = p;
            try {
                g.stdin = new ParcelFileDescriptor.AutoCloseOutputStream(p.getOutputStream());
                g.stdout = new ParcelFileDescriptor.AutoCloseInputStream(p.getInputStream());
                g.stderr = new ParcelFileDescriptor.AutoCloseInputStream(p.getErrorStream());
            } catch (Exception e) {
                g.process = null;
                g.status = 1;
            }
            return g;
        }

        private Stage canned(String text, int status) {
            Stage g = new Stage();
            g.stdout = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            g.status = status;
            return g;
        }

        /** Runs the command itself: a builtin, or the broker. */
        private Stage start(List<String> argv) {
            String cmd = argv.get(0);
            switch (cmd) {
                case "true": case ":": case "umask": case "set":
                    return canned("", 0);
                case "false":
                    return canned("", 1);
                case "echo": {
                    boolean nl = !(argv.size() > 1 && argv.get(1).equals("-n"));
                    StringBuilder b = new StringBuilder();
                    for (int i = nl ? 1 : 2; i < argv.size(); i++) b.append(i > (nl ? 1 : 2) ? " " : "").append(argv.get(i));
                    if (nl) b.append('\n');
                    return canned(b.toString(), 0);
                }
                case "id": case "/system/bin/id":
                    return canned(FAKE_ID + "\n", 0);
                case "whoami":
                    return canned("root\n", 0);
                case "export":
                    for (int i = 1; i < argv.size(); i++) {
                        int eq = argv.get(i).indexOf('=');
                        if (eq > 0 && MiniShell.isName(argv.get(i).substring(0, eq))) {
                            vars.put(argv.get(i).substring(0, eq), argv.get(i).substring(eq + 1));
                        }
                    }
                    return canned("", 0);
                case "unset":
                    for (int i = 1; i < argv.size(); i++) vars.remove(argv.get(i));
                    return canned("", 0);
                default:
                    return broker(argv.toArray(new String[0]));
            }
        }

        private int pipeline(List<MiniShell.Step> steps, Out out) throws Exception {
            // nothing runs until every word and redirection has been worked out
            List<Node> nodes = new ArrayList<>();
            for (MiniShell.Step st : steps) {
                Node n = new Node();
                n.r = resolve(st);
                nodes.add(n);
            }
            if (nodes.size() == 1 && nodes.get(0).r.argv.isEmpty()) return 0; // assignments only
            for (Node n : nodes) if (n.r.argv.isEmpty()) throw new IllegalArgumentException("a redirection with no command");
            if (nodes.size() == 1 && nodes.get(0).r.argv.get(0).equals("exit")) return EXIT;

            List<Stage> all = new ArrayList<>();
            try {
                // the files first: a refused write or read ends it before the command has run
                for (Node n : nodes) {
                    Resolved r = n.r;
                    if (r.inFile != null) n.in = add(all, broker("cat", r.inFile));
                    if (r.out.kind == Target.FILE) n.sink = add(all, broker(tee(r.out)));
                    if (r.err.kind == Target.FILE && r.err != r.out) n.errSink = add(all, broker(tee(r.err)));
                }
                for (Stage g : all) {
                    if (g.failedFast()) {
                        copy(g.stderr, out, 2).join();
                        int code = g.finish();
                        return code == 0 ? 1 : code;
                    }
                }
                for (Node n : nodes) n.main = add(all, start(n.r.argv));
                return connectAndWait(nodes, all, out);
            } finally {
                for (Stage g : all) g.destroy();
            }
        }

        private Stage add(List<Stage> all, Stage g) {
            all.add(g);
            return g;
        }

        private String[] tee(Target t) {
            return t.append ? new String[]{"tee", "-a", t.path} : new String[]{"tee", t.path};
        }

        private int connectAndWait(List<Node> nodes, List<Stage> all, Out out) throws Exception {
            List<Thread> threads = new ArrayList<>();
            OutputStream clientOut = new Frames(out, 1), clientErr = new Frames(out, 2);
            for (int k = 0; k < nodes.size(); k++) {
                Node n = nodes.get(k);
                Node next = k + 1 < nodes.size() ? nodes.get(k + 1) : null;
                Resolved r = n.r;

                OutputStream stdoutDest;
                if (r.out.kind == Target.DROP) stdoutDest = OutputStream.nullOutputStream();
                else if (r.out.kind == Target.FILE) stdoutDest = n.sink.stdin;
                else if (next == null) stdoutDest = clientOut;
                else if (next.in != null) stdoutDest = OutputStream.nullOutputStream(); // the next command reads a file instead
                else stdoutDest = next.main.stdin;

                OutputStream stderrDest;
                if (r.err == r.out) stderrDest = stdoutDest;
                else if (r.err.kind == Target.DROP) stderrDest = OutputStream.nullOutputStream();
                else if (r.err.kind == Target.FILE) stderrDest = n.errSink.stdin;
                else stderrDest = clientErr;

                if (stderrDest == stdoutDest && stdoutDest != clientOut) {
                    Shared both = new Shared(stdoutDest, 2);
                    stdoutDest = both;
                    stderrDest = both;
                } else if (stderrDest == stdoutDest) {
                    stderrDest = clientOut; // 2>&1 to the client: same stream
                }
                threads.add(feed(n.main.stdout, stdoutDest));
                threads.add(feed(n.main.stderr, stderrDest));

                if (n.in != null) {
                    threads.add(feed(n.in.stdout, n.main.stdin));
                    threads.add(feed(n.in.stderr, clientErr));
                } else if (k == 0 || nodes.get(k - 1).r.out.kind != Target.PIPE) {
                    closeQuietly(n.main.stdin); // nothing is piped in; the script itself is not its input
                }
                if (n.sink != null) {
                    threads.add(feed(n.sink.stdout, OutputStream.nullOutputStream()));
                    threads.add(feed(n.sink.stderr, clientErr));
                }
                if (n.errSink != null) {
                    threads.add(feed(n.errSink.stdout, OutputStream.nullOutputStream()));
                    threads.add(feed(n.errSink.stderr, clientErr));
                }
            }
            Node last = nodes.get(nodes.size() - 1);
            int status = last.main.finish();
            // the consumer is done (head, grep -m): stop what still feeds it; commands writing to files finish on their own
            for (int k = 0; k < nodes.size() - 1; k++) {
                Node n = nodes.get(k);
                if (n.r.out.kind == Target.PIPE) n.main.destroy();
                else n.main.finish();
            }
            for (Thread t : threads) {
                try {
                    t.join(10_000);
                } catch (InterruptedException ignored) {
                }
            }
            // a refused or failed write is the command's failure
            for (Node n : nodes) {
                for (Stage g : new Stage[]{n.sink, n.errSink, n.in}) {
                    if (g != null) {
                        int code = g.finish();
                        if (code != 0 && status == 0) status = code;
                    }
                }
            }
            return status;
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static void closeQuietly(OutputStream o) {
        try {
            o.close();
        } catch (IOException ignored) {
        }
    }

    private static Thread copy(InputStream in, Out out, int stream) {
        Thread t = new Thread(() -> {
            try (InputStream i = in) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = i.read(buf)) > 0) out.frame(stream, buf, 0, r);
            } catch (IOException ignored) {
            }
        });
        t.start();
        return t;
    }

    /** Copies [from] into [to] and closes [to]; if [to] has gone, keeps draining so the writer can finish. */
    private static Thread feed(InputStream from, OutputStream to) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            boolean open = true;
            try (InputStream i = from) {
                int r;
                while ((r = i.read(buf)) > 0) {
                    if (!open) continue;
                    try {
                        to.write(buf, 0, r);
                        to.flush();
                    } catch (IOException gone) {
                        open = false;
                    }
                }
            } catch (IOException ignored) {
            } finally {
                closeQuietly(to);
            }
        });
        t.start();
        return t;
    }

    /** One destination written by two sources (stdout and stderr merged); closes when both are done. */
    private static final class Shared extends OutputStream {
        private final OutputStream base;
        private int refs;

        Shared(OutputStream base, int refs) {
            this.base = base;
            this.refs = refs;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            base.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            base.write(b, off, len);
        }

        @Override
        public synchronized void flush() throws IOException {
            base.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            if (--refs == 0) base.close();
        }
    }

    /** Output frames to the client as a stream; closing it does nothing (the connection ends it). */
    private static final class Frames extends OutputStream {
        private final Out out;
        private final int type;

        Frames(Out out, int type) {
            this.out = out;
            this.type = type;
        }

        @Override
        public void write(int b) throws IOException {
            out.frame(type, new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.frame(type, b, off, len);
        }

        @Override
        public void close() {
        }
    }

    /** Where a connection's output goes: the client, or a buffer for $(...) with errors passed on. */
    private static class Out {
        private final DataOutputStream out;

        Out(DataOutputStream out) {
            this.out = out;
        }

        synchronized void frame(int type, byte[] data, int off, int len) throws IOException {
            out.writeByte(type);
            out.writeInt(len);
            out.write(data, off, len);
            out.flush();
        }

        void text(int type, String s) throws IOException {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            frame(type, b, 0, b.length);
        }

        void exit(int code) throws IOException {
            byte[] b = {(byte) (code >> 24), (byte) (code >> 16), (byte) (code >> 8), (byte) code};
            frame(3, b, 0, 4);
        }
    }

    private static final class Capture extends Out {
        private final Out errors;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Capture(Out errors) {
            super(null);
            this.errors = errors;
        }

        @Override
        synchronized void frame(int type, byte[] data, int off, int len) throws IOException {
            if (type == 1) {
                if (buf.size() + len <= MAX_CAPTURE) buf.write(data, off, len);
            } else if (type == 2 && errors != null) {
                errors.frame(2, data, off, len);
            }
        }

        String text() {
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
