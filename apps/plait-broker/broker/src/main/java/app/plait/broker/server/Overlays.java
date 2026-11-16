package app.plait.broker.server;

import android.os.IBinder;
import android.os.ServiceManager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Colors and simple values for other apps through fabricated overlays (what Monet theming apps such as ColorBlendr do as root).
 *
 * <p>Only overlays named "plait*", only numbers: colors, booleans, integers and dimensions. A number cannot point at a
 * file, a string or another resource, and the framework ("android") only takes colors, because its booleans and integers
 * include behavior switches ({@code config_*}). That is the one kind of overlay the broker will register. Android blocks the shell user from registering overlays, so two ways to do it:
 * <ul>
 *   <li>PlaitOS patches OverlayManagerService to allow exactly this request from shell ({@link #direct});</li>
 *   <li>on a rooted test phone, a root helper ({@link #main}) started by the Magisk module does it for the daemon
 *   through a request directory ({@link #viaHelper}).</li>
 * </ul>
 * The request format (stdin of {@code plait-overlay register NAME TARGET}), one entry per line:
 * <pre>
 * color/system_accent1_500 ff3a7bd5     AARRGGBB
 * bool/some_flag true                   true or false
 * integer/some_count 3                  whole number
 * dimen/some_size 12dp                  number and px, dp, dip, sp, pt, in or mm
 * </pre>
 * (a {@code package:} prefix is allowed when the resource lives in another package than the target).
 */
public final class Overlays {
    static final String SOURCE_PACKAGE = "com.android.shell";
    static final File DIR = new File("/data/plait/ovl");
    private static final Pattern NAME = Pattern.compile("plait[A-Za-z0-9_.]{0,80}");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*");
    private static final Pattern ENTRY = Pattern.compile(
            "((?:[A-Za-z][A-Za-z0-9_.]{0,100}:)?(color|bool|integer|dimen)/[A-Za-z0-9_.]{1,100}) (\\S{1,32})");
    private static final Pattern COLOR = Pattern.compile("[0-9a-fA-F]{8}");
    private static final Pattern DIMENSION = Pattern.compile("(-?[0-9]{1,7}(?:\\.[0-9]{1,4})?)(px|dp|dip|sp|pt|in|mm)");
    private static final int MAX_ENTRIES = 600;
    // TypedValue data types
    static final int TYPE_DIMENSION = 0x05, TYPE_INT_DEC = 0x10, TYPE_INT_BOOLEAN = 0x12, TYPE_INT_COLOR_ARGB8 = 0x1c;
    /** The framework's own booleans and integers choose behavior (config_*); only its colors may be overlaid from shell. */
    static final String FRAMEWORK = "android";

    private Overlays() {
    }

    /** One resource value: its name as sent ("color/x" or "pkg:color/x"), the text of the value, and what Android stores. */
    static final class Entry {
        final String name, raw;
        final int type, data;

        Entry(String name, String raw, int type, int data) {
            this.name = name;
            this.raw = raw;
            this.type = type;
            this.data = data;
        }

        boolean isColor() {
            return type == TYPE_INT_COLOR_ARGB8;
        }
    }

    /** A validated register or unregister request. */
    static final class Request {
        final boolean register;
        final String name;
        final String target;
        final List<Entry> entries = new ArrayList<>();

        Request(boolean register, String name, String target) {
            this.register = register;
            this.name = name;
            this.target = target;
        }

        /** True when every value is a color: those may go to the system UI after one question; other values need more. */
        boolean colorsOnly() {
            for (Entry e : entries) if (!e.isColor()) return false;
            return true;
        }

        String serialize() {
            StringBuilder b = new StringBuilder(register ? "register " : "unregister ").append(name);
            if (register) b.append(' ').append(target);
            b.append('\n');
            for (Entry e : entries) b.append(e.name).append(' ').append(e.raw).append('\n');
            return b.toString();
        }
    }

    /** Parses argv after {@code plait-overlay} plus the stdin text; throws with the reason when anything is off. */
    static Request parse(List<String> args, String stdin) {
        if (args.isEmpty()) throw new IllegalArgumentException("usage: plait-overlay register NAME TARGET < entries | unregister NAME");
        String verb = args.get(0);
        if (verb.equals("unregister") && args.size() == 2) {
            if (!NAME.matcher(args.get(1)).matches()) throw new IllegalArgumentException("overlay names start with \"plait\"");
            return new Request(false, args.get(1), null);
        }
        if (!verb.equals("register") || args.size() != 3) {
            throw new IllegalArgumentException("usage: plait-overlay register NAME TARGET < entries | unregister NAME");
        }
        String name = args.get(1), target = args.get(2);
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("overlay names start with \"plait\"");
        if (!PACKAGE.matcher(target).matches()) throw new IllegalArgumentException("not a package name: " + target);
        Request r = new Request(true, name, target);
        for (String line : stdin.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            java.util.regex.Matcher m = ENTRY.matcher(line);
            if (!m.matches()) throw new IllegalArgumentException("not a color, bool, integer or dimen entry: " + line);
            if (r.entries.size() >= MAX_ENTRIES) throw new IllegalArgumentException("too many entries");
            Entry e = entry(m.group(1), m.group(2), m.group(3));
            if (!e.isColor()) {
                if (target.equals(FRAMEWORK)) throw new IllegalArgumentException("the framework only takes colors: " + line);
                if (e.name.contains("config_")) throw new IllegalArgumentException("config_* values choose behavior and are refused: " + line);
            }
            r.entries.add(e);
        }
        if (r.entries.isEmpty()) throw new IllegalArgumentException("no entries");
        return r;
    }

    private static Entry entry(String name, String kind, String value) {
        switch (kind) {
            case "color":
                if (!COLOR.matcher(value).matches()) throw new IllegalArgumentException("a color is AARRGGBB: " + value);
                return new Entry(name, value, TYPE_INT_COLOR_ARGB8, (int) Long.parseLong(value, 16));
            case "bool":
                if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("a bool is true or false: " + value);
                return new Entry(name, value, TYPE_INT_BOOLEAN, value.equals("true") ? 1 : 0);
            case "integer":
                try {
                    return new Entry(name, value, TYPE_INT_DEC, Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("an integer is a whole number: " + value);
                }
            default: {
                java.util.regex.Matcher m = DIMENSION.matcher(value);
                if (!m.matches()) throw new IllegalArgumentException("a dimen is a number and px, dp, sp, pt, in or mm: " + value);
                return new Entry(name, value, TYPE_DIMENSION, complexDimension(Float.parseFloat(m.group(1)), m.group(2)));
            }
        }
    }

    /** TypedValue's packed dimension: a 23-bit mantissa, a radix and the unit (px 0, dp 1, sp 2, pt 3, in 4, mm 5). */
    static int complexDimension(float value, String unit) {
        int u;
        switch (unit) {
            case "px": u = 0; break;
            case "dp": case "dip": u = 1; break;
            case "sp": u = 2; break;
            case "pt": u = 3; break;
            case "in": u = 4; break;
            default: u = 5;
        }
        boolean negative = value < 0;
        if (negative) value = -value;
        long bits = (long) (value * (1 << 23) + 0.5f);
        int radix, shift;
        if ((bits & 0x7fffff) == 0) {
            radix = 0;
            shift = 23;
        } else if ((bits & 0xffffffffff800000L) == 0) {
            radix = 3;
            shift = 0;
        } else if ((bits & 0xffffffff80000000L) == 0) {
            radix = 2;
            shift = 8;
        } else if ((bits & 0xffffff8000000000L) == 0) {
            radix = 1;
            shift = 16;
        } else {
            throw new IllegalArgumentException("dimension out of range");
        }
        int mantissa = (int) ((bits >> shift) & 0xffffff);
        if (negative) mantissa = (-mantissa) & 0xffffff;
        return (mantissa << 8) | (radix << 4) | u;
    }

    /** Applies [r] from this process: the PlaitOS way. Throws if Android refuses (stock Android refuses shell). */
    static void direct(Request r, int userId) throws Exception {
        Class<?> tbClass = Class.forName("android.content.om.OverlayManagerTransaction$Builder");
        Object tb = tbClass.getDeclaredConstructor().newInstance();
        Class<?> idClass = Class.forName("android.content.om.OverlayIdentifier");
        Object id = idClass.getConstructor(String.class, String.class).newInstance(SOURCE_PACKAGE, r.name);
        if (r.register) {
            Class<?> fobClass = Class.forName("android.content.om.FabricatedOverlay$Builder");
            Object fob = fobClass.getConstructor(String.class, String.class, String.class)
                    .newInstance(SOURCE_PACKAGE, r.name, r.target);
            Method set = fobClass.getMethod("setResourceValue", String.class, int.class, int.class);
            for (Entry e : r.entries) {
                set.invoke(fob, e.name.contains(":") ? e.name : r.target + ":" + e.name, e.type, e.data);
            }
            Object overlay = fobClass.getMethod("build").invoke(fob);
            tbClass.getMethod("registerFabricatedOverlay", Class.forName("android.content.om.FabricatedOverlay"))
                    .invoke(tb, overlay);
            tbClass.getMethod("setEnabled", idClass, boolean.class, int.class).invoke(tb, id, true, userId);
        } else {
            tbClass.getMethod("unregisterFabricatedOverlay", idClass).invoke(tb, id);
        }
        Object tx = tbClass.getMethod("build").invoke(tb);
        IBinder binder = ServiceManager.getService("overlay");
        Object om = Class.forName("android.content.om.IOverlayManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        om.getClass().getMethod("commit", Class.forName("android.content.om.OverlayManagerTransaction")).invoke(om, tx);
    }

    // ------------------------------------------------------------------ root helper (Magisk test phone)

    static boolean helperRunning() {
        return new File(DIR, "helper.alive").isFile()
                && System.currentTimeMillis() - new File(DIR, "helper.alive").lastModified() < 10_000;
    }

    /** Hands [r] to the root helper and waits for its answer; returns null on success, else the reason. */
    static String viaHelper(Request r, int userId) throws IOException, InterruptedException {
        File req = new File(DIR, "req"), res = new File(DIR, "res");
        String id = Long.toHexString(System.nanoTime());
        File tmp = new File(req, id + ".tmp"), out = new File(res, id);
        Files.write(tmp.toPath(), (userId + "\n" + r.serialize()).getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(new File(req, id + ".req"))) throw new IOException("could not queue the request");
        for (int i = 0; i < 100; i++) {
            if (out.isFile()) {
                String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8).trim();
                out.delete();
                return text.equals("ok") ? null : text;
            }
            Thread.sleep(100);
        }
        new File(req, id + ".req").delete();
        return "the root helper did not answer";
    }

    /**
     * The root helper: {@code app_process ... app.plait.broker.server.Overlays}, run as root by the Magisk module. It
     * re-checks every request with {@link #parse} (the same rules as the ROM patch), then registers it.
     */
    public static void main(String[] argv) throws Exception {
        File req = new File(DIR, "req"), res = new File(DIR, "res"), alive = new File(DIR, "helper.alive");
        req.mkdirs();
        res.mkdirs();
        // the daemon (shell) must be able to write requests and read answers
        for (File f : new File[]{DIR, req, res}) {
            f.setReadable(true, false);
            f.setWritable(true, false);
            f.setExecutable(true, false);
        }
        while (true) {
            Files.write(alive.toPath(), new byte[0]);
            alive.setLastModified(System.currentTimeMillis());
            File[] pending = req.listFiles((d, n) -> n.endsWith(".req"));
            if (pending != null) {
                for (File f : pending) {
                    String id = f.getName().replace(".req", "");
                    String answer;
                    try {
                        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                        int nl = text.indexOf('\n');
                        int userId = Integer.parseInt(text.substring(0, nl));
                        String body = text.substring(nl + 1);
                        int nl2 = body.indexOf('\n');
                        String header = nl2 < 0 ? body : body.substring(0, nl2);
                        List<String> args = new ArrayList<>(java.util.Arrays.asList(header.split(" ")));
                        Request r = parse(args, nl2 < 0 ? "" : body.substring(nl2 + 1));
                        direct(r, userId);
                        answer = "ok";
                    } catch (Throwable t) {
                        Throwable c = t.getCause() != null ? t.getCause() : t;
                        answer = String.valueOf(c.getMessage());
                    }
                    f.delete();
                    File out = new File(res, id);
                    Files.write(out.toPath(), answer.getBytes(StandardCharsets.UTF_8));
                    out.setReadable(true, false);
                    out.setWritable(true, false);
                }
            }
            Thread.sleep(150);
        }
    }

    /** A process whose stdin is the request text; [work] gets it and returns null on success, else the reason. */
    static Process fromStdin(java.util.function.Function<String, String> work) throws IOException {
        java.io.PipedInputStream in = new java.io.PipedInputStream(64 * 1024);
        java.io.PipedOutputStream stdin = new java.io.PipedOutputStream(in);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        String[] error = {null};
        Thread t = new Thread(() -> {
            try {
                error[0] = work.apply(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Throwable e) {
                error[0] = String.valueOf(e.getMessage());
            } finally {
                done.countDown();
            }
        }, "overlay-stdin");
        t.start();
        return new Process() {
            @Override public java.io.OutputStream getOutputStream() { return stdin; }
            @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
            @Override public java.io.InputStream getErrorStream() {
                try { done.await(); } catch (InterruptedException ignored) { }
                return new java.io.ByteArrayInputStream(
                        (error[0] == null ? "" : "plait-overlay: " + error[0] + "\n").getBytes(StandardCharsets.UTF_8));
            }
            @Override public int waitFor() throws InterruptedException { done.await(); return error[0] == null ? 0 : 1; }
            @Override public int exitValue() {
                if (done.getCount() > 0) throw new IllegalThreadStateException();
                return error[0] == null ? 0 : 1;
            }
            @Override public void destroy() { t.interrupt(); }
        };
    }
}
