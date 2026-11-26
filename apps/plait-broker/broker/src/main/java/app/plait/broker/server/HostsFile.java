package app.plait.broker.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * The one real system overlay: {@code /system/etc/hosts}, the DNS blocklist adblockers write. At boot (PlaitOS init,
 * or the Magisk module's post-fs-data on a test phone) {@code /data/plait/hosts} is bind-mounted over it; Android's
 * DNS resolver reads it before asking DNS, for every app. The system partition itself never changes.
 *
 * <p>Only blocking entries are kept: an address of 0.0.0.0, 127.0.0.1, :: or ::1. A hosts file can also send a domain
 * to any address ("yourbank.com" to someone else's server); those lines are dropped, so a blocklist app can block
 * but never redirect. Writes go into the file in place (the bind mount follows the file, not its name).
 */
final class HostsFile {
    static final File DIR = new File("/data/plait");
    static final File FILE = new File(DIR, "hosts");
    /** The ROM's own hosts file, saved before the overlay hides it. */
    static final File STOCK = new File(DIR, "hosts.stock");
    static final Set<String> TARGETS = new HashSet<>(Arrays.asList("/system/etc/hosts", "/etc/hosts"));
    static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final String FALLBACK_STOCK = "127.0.0.1 localhost\n::1 ip6-localhost\n";
    private static final Set<String> BLOCKING = new HashSet<>(Arrays.asList(
            "0.0.0.0", "127.0.0.1", "::", "::1", "0:0:0:0:0:0:0:0", "0:0:0:0:0:0:0:1"));
    private static final Set<String> LOCAL_NAMES = new HashSet<>(Arrays.asList(
            "localhost", "ip6-localhost", "localhost.localdomain", "local", "ip6-loopback"));

    private HostsFile() {
    }

    /**
     * On PlaitOS init cannot copy only-if-missing, so the daemon makes the list from the stock copy the first time; the
     * mount picks it up from the next boot. Nothing to do once it exists (the Magisk module creates it itself).
     */
    static void ensure() {
        if (FILE.exists() || !DIR.isDirectory()) return;
        try {
            Files.copy(STOCK.isFile() ? STOCK.toPath() : new File("/system/etc/hosts").toPath(), FILE.toPath());
            FILE.setReadable(true, false);
        } catch (IOException | RuntimeException ignored) {
            // the manager shows the overlay as not active
        }
    }

    static boolean isTarget(String path) {
        return TARGETS.contains(path);
    }

    /** True when the overlay is in place: /system/etc/hosts is our file. */
    static boolean active() {
        try {
            android.system.StructStat a = android.system.Os.stat("/system/etc/hosts");
            android.system.StructStat b = android.system.Os.stat(FILE.getPath());
            return a.st_dev == b.st_dev && a.st_ino == b.st_ino;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    static final class Filtered {
        final int entries;
        final int dropped;

        Filtered(int entries, int dropped) {
            this.entries = entries;
            this.dropped = dropped;
        }
    }

    /**
     * Copies [in] to [out] line by line, keeping comments, blank lines and blocking entries and dropping lines that
     * would send a name somewhere. Streams: a blocklist can have a million lines. The stock localhost entries always
     * come first; they are not counted.
     */
    static Filtered filter(Reader in, Writer out) throws IOException {
        BufferedReader r = new BufferedReader(in, 64 * 1024);
        out.write(FALLBACK_STOCK);
        int entries = 0;
        int dropped = 0;
        long chars = 0;
        String raw;
        while ((raw = r.readLine()) != null) {
            chars += raw.length() + 1;
            if (chars > MAX_BYTES) throw new IOException("larger than " + (MAX_BYTES >> 20) + " MB");
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                out.write(line);
                out.write('\n');
                continue;
            }
            int hash = line.indexOf('#');
            String body = hash >= 0 ? line.substring(0, hash).trim() : line;
            String[] f = fields(body);
            boolean ok = f.length >= 2 && BLOCKING.contains(f[0]);
            for (int i = 1; ok && i < f.length; i++) ok = isName(f[i]);
            if (!ok) {
                dropped++;
                continue;
            }
            int names = 0;
            for (int i = 1; i < f.length; i++) if (!LOCAL_NAMES.contains(f[i])) names++;
            if (names == 0) continue; // the stock entries are already at the top
            out.write(body);
            out.write('\n');
            entries += names;
        }
        out.flush();
        return new Filtered(entries, dropped);
    }

    /**
     * Whitespace-separated fields. No regex here or in {@link #isName}: on Android every regex match allocates native
     * memory that is only freed later, and a blocklist has a million lines.
     */
    private static String[] fields(String line) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(4);
        int i = 0;
        int n = line.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(line.charAt(i))) i++;
            int start = i;
            while (i < n && !Character.isWhitespace(line.charAt(i))) i++;
            if (i > start) out.add(line.substring(start, i));
        }
        return out.toArray(new String[0]);
    }

    /** A host name: letters, digits and {@code - _ . *}, up to 253 characters. */
    static boolean isName(String s) {
        if (s.isEmpty() || s.length() > 253) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '*';
            if (!ok) return false;
        }
        return true;
    }

    /** [content] filtered, as text (tests). */
    static String filtered(String content) throws IOException {
        StringWriter w = new StringWriter();
        filter(new StringReader(content), w);
        return w.toString();
    }

    /**
     * Replaces the blocklist with [in], filtered. Written to a staging file first, then copied into the overlay file in
     * place (the bind mount follows the file, not its name), so readers never see a half-filtered list for long.
     */
    static synchronized Filtered write(InputStream in, java.util.function.BooleanSupplier allowed) throws IOException {
        File staged = new File(DIR, "hosts.new");
        Filtered f;
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
             Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(staged), StandardCharsets.UTF_8), 64 * 1024)) {
            f = filter(r, w);
        } catch (IOException e) {
            staged.delete();
            throw e;
        }
        try {
            if (!allowed.getAsBoolean()) throw new IOException("Broker access was revoked");
            copyInPlace(staged);
        } finally {
            staged.delete();
        }
        return f;
    }

    static synchronized void reset() throws IOException {
        if (STOCK.isFile()) {
            copyInPlace(STOCK);
        } else {
            try (OutputStream out = new FileOutputStream(FILE, false)) {
                out.write(FALLBACK_STOCK.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void copyInPlace(File from) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(FILE, false)) {
            byte[] b = new byte[256 * 1024];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
    }

    private static long countedStamp = -1;
    private static int countedEntries = -1;

    /** Names in the current list (not the localhost entries); counted again only when the file changes. */
    static synchronized int entries() {
        long stamp = FILE.lastModified() * 31 + FILE.length();
        if (stamp == countedStamp) return countedEntries;
        try (Reader r = new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8)) {
            countedEntries = filter(r, NULL_WRITER).entries;
            countedStamp = stamp;
        } catch (IOException | RuntimeException e) {
            countedEntries = -1;
        }
        return countedEntries;
    }

    static Filtered write(File source, java.util.function.BooleanSupplier allowed) throws IOException {
        if (source.length() > MAX_BYTES) throw new IOException("larger than " + (MAX_BYTES >> 20) + " MB");
        try (InputStream in = new FileInputStream(source)) {
            return write(in, allowed);
        }
    }

    private static final Writer NULL_WRITER = new Writer() {
        @Override
        public void write(char[] c, int off, int len) {
        }

        @Override
        public void write(String s) {
        }

        @Override
        public void write(int c) {
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    /**
     * A process for {@code tee /system/etc/hosts} or {@code dd of=/system/etc/hosts}: what the app writes to its stdin
     * becomes the new blocklist when it closes the stream. [onDone] logs the result.
     */
    static Process fromStdin(java.util.function.BooleanSupplier allowed, java.util.function.Consumer<String> onDone) throws IOException {
        PipedInputStream in = new PipedInputStream(64 * 1024);
        PipedOutputStream stdin = new PipedOutputStream(in);
        CountDownLatch done = new CountDownLatch(1);
        int[] exit = {1};
        StringBuilder err = new StringBuilder();
        Thread t = new Thread(() -> {
            try {
                Filtered f = write(in, allowed);
                exit[0] = 0;
                onDone.accept(f.entries + " names" + (f.dropped > 0 ? ", " + f.dropped + " redirecting lines dropped" : ""));
            } catch (IOException e) {
                err.append("hosts: ").append(e.getMessage()).append('\n');
                onDone.accept("failed: " + e.getMessage());
            } finally {
                done.countDown();
            }
        }, "hosts-stdin");
        t.start();
        return new Process() {
            @Override
            public OutputStream getOutputStream() {
                return stdin;
            }

            @Override
            public InputStream getInputStream() {
                return new java.io.ByteArrayInputStream(new byte[0]);
            }

            @Override
            public InputStream getErrorStream() {
                try {
                    done.await();
                } catch (InterruptedException ignored) {
                }
                return new java.io.ByteArrayInputStream(err.toString().getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public int waitFor() throws InterruptedException {
                done.await();
                return exit[0];
            }

            @Override
            public int exitValue() {
                if (done.getCount() > 0) throw new IllegalThreadStateException();
                return exit[0];
            }

            @Override
            public boolean isAlive() {
                return done.getCount() > 0;
            }

            @Override
            public void destroy() {
                try {
                    stdin.close();
                } catch (IOException ignored) {
                }
            }
        };
    }
}
