package app.plait.broker.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Commands that write to the read-only system partitions. On PlaitOS (verified boot, locked bootloader) they can never
 * work, and apps written for rooted phones may crash when they fail, so the broker answers them itself: the command
 * "succeeds" and the real system is untouched. Files an app writes there are kept in its own overlay directory, so
 * nothing it meant to install is lost, but what the system and apps read does not change.
 *
 * <p>Only commands whose every path target is on a read-only partition are handled here; anything mixed runs for real
 * (and fails on the read-only part, as it would on any locked phone).
 */
final class SystemWrites {
    /** Read-only roots, including the top-level symlinks into /system that root apps often use (/etc/hosts). */
    private static final String[] READ_ONLY = {"/system", "/system_ext", "/product", "/vendor", "/odm", "/apex", "/etc", "/bin"};
    private static final long MAX_COPY_BYTES = 16L * 1024 * 1024;

    enum Kind { REMOUNT, COPY, CREATE_FILE, CREATE_DIR, NO_OP,
        /** tee / dd of=: the content arrives on stdin */
        STDIN }

    static final class Plan {
        final Kind kind;
        final List<String> targets; // normalized absolute paths on read-only partitions
        final List<String> sources; // COPY only, normalized absolute
        final boolean intoDir;      // COPY: the target names a directory (cp a b /system/etc/, cp hosts /system/etc)

        Plan(Kind kind, List<String> targets, List<String> sources, boolean intoDir) {
            this.kind = kind;
            this.targets = targets;
            this.sources = sources;
            this.intoDir = intoDir;
        }

        Plan(Kind kind, List<String> targets, List<String> sources) {
            this(kind, targets, sources, false);
        }
    }

    private static final Set<String> NO_OP_TOOLS = new HashSet<>(Arrays.asList(
            "rm", "rmdir", "ln", "truncate", "restorecon", "setfattr", "unlink"));
    /** Tools whose first operand is not a path (mode, owner, context). */
    private static final Set<String> FIRST_OPERAND_NOT_PATH = new HashSet<>(Arrays.asList("chmod", "chown", "chgrp", "chcon"));

    private SystemWrites() {
    }

    /** The plan for [argv] if it only writes to read-only partitions, else null. [cwd] resolves relative paths. */
    static Plan plan(List<String> argv, String cwd) {
        String tool = argv.get(0).substring(argv.get(0).lastIndexOf('/') + 1);
        String base = cwd != null ? cwd : "/";
        switch (tool) {
            case "mount": {
                List<String> operands = new ArrayList<>();
                boolean remount = false;
                for (int i = 1; i < argv.size(); i++) {
                    String a = argv.get(i);
                    if (a.equals("-o") || a.equals("-t")) {
                        if (i + 1 < argv.size() && a.equals("-o") && argv.get(i + 1).contains("remount")) remount = true;
                        i++;
                    } else if (a.startsWith("-o")) {
                        if (a.contains("remount")) remount = true;
                    } else if (!a.startsWith("-")) {
                        operands.add(a);
                    }
                }
                if (!remount || operands.isEmpty()) return null;
                String target = resolve(base, operands.get(operands.size() - 1));
                return target.equals("/") || readOnly(target) ? new Plan(Kind.REMOUNT, List.of(target), List.of()) : null;
            }
            case "cp":
            case "mv": {
                List<String> ops = operands(argv, 1);
                if (ops.size() < 2) return null;
                String dest = resolve(base, ops.get(ops.size() - 1));
                if (!readOnly(dest)) return null;
                List<String> sources = new ArrayList<>();
                for (String s : ops.subList(0, ops.size() - 1)) sources.add(resolve(base, s));
                // Decided from the raw argument and the real system: normalizing drops a trailing '/'
                boolean intoDir = sources.size() > 1 || ops.get(ops.size() - 1).endsWith("/") || new File(dest).isDirectory();
                return new Plan(Kind.COPY, List.of(dest), sources, intoDir);
            }
            case "touch":
                return allReadOnly(Kind.CREATE_FILE, operands(argv, 1), base);
            case "mkdir":
                return allReadOnly(Kind.CREATE_DIR, operands(argv, 1), base);
            case "sed":
                return argv.contains("-i") ? allReadOnly(Kind.NO_OP, files(operands(argv, 1)), base) : null;
            case "dd": {
                boolean fromStdin = true;
                for (String a : argv) if (a.startsWith("if=")) fromStdin = false;
                for (String a : argv) {
                    if (a.startsWith("of=")) {
                        String target = resolve(base, a.substring(3));
                        return readOnly(target) ? new Plan(fromStdin ? Kind.STDIN : Kind.NO_OP, List.of(target), List.of()) : null;
                    }
                }
                return null;
            }
            case "tee":
                return allReadOnly(Kind.STDIN, operands(argv, 1), base);
            default:
                if (NO_OP_TOOLS.contains(tool)) return allReadOnly(Kind.NO_OP, operands(argv, 1), base);
                if (FIRST_OPERAND_NOT_PATH.contains(tool)) {
                    List<String> ops = operands(argv, 1);
                    return ops.size() < 2 ? null : allReadOnly(Kind.NO_OP, ops.subList(1, ops.size()), base);
                }
                return null;
        }
    }

    /**
     * Carries out [plan] in [overlay] (the app's own directory) instead of on the system. Returns null, or a note on what
     * could not be kept; the command counts as done either way, since the system itself never changes.
     */
    static String apply(Plan plan, File overlay) {
        try {
            switch (plan.kind) {
                case COPY: {
                    Path dest = inOverlay(overlay, plan.targets.get(0));
                    boolean intoDir = plan.intoDir || Files.isDirectory(dest);
                    for (String s : plan.sources) {
                        Path src = Paths.get(s);
                        if (!Files.isRegularFile(src)) return "not a readable file: " + s;
                        if (Files.size(src) > MAX_COPY_BYTES) return "too large to keep: " + s;
                        Path to = intoDir ? dest.resolve(src.getFileName().toString()) : dest;
                        Files.createDirectories(to.getParent());
                        Files.copy(src, to, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return null;
                }
                case CREATE_FILE:
                    for (String t : plan.targets) {
                        Path p = inOverlay(overlay, t);
                        Files.createDirectories(p.getParent());
                        if (!Files.exists(p)) Files.createFile(p);
                    }
                    return null;
                case CREATE_DIR:
                    for (String t : plan.targets) Files.createDirectories(inOverlay(overlay, t));
                    return null;
                default:
                    return null; // remount, delete, chmod...: nothing to keep
            }
        } catch (IOException | SecurityException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** What the command would print on a real read-only system. */
    static String readOnlyError(List<String> argv, Plan plan) {
        String tool = argv.get(0).substring(argv.get(0).lastIndexOf('/') + 1);
        return tool + ": " + plan.targets.get(0) + ": Read-only file system\n";
    }

    static boolean readOnly(String path) {
        for (String root : READ_ONLY) {
            if (path.equals(root) || path.startsWith(root + "/")) return true;
        }
        return false;
    }

    private static Plan allReadOnly(Kind kind, List<String> paths, String base) {
        if (paths.isEmpty()) return null;
        List<String> targets = new ArrayList<>();
        for (String p : paths) {
            String r = resolve(base, p);
            if (!readOnly(r)) return null;
            targets.add(r);
        }
        return new Plan(kind, targets, List.of());
    }

    /** Arguments that are not options ("--" ends the options). */
    private static List<String> operands(List<String> argv, int from) {
        List<String> out = new ArrayList<>();
        boolean optionsDone = false;
        for (String a : argv.subList(from, argv.size())) {
            if (!optionsDone && a.equals("--")) {
                optionsDone = true;
            } else if (optionsDone || !a.startsWith("-") || a.equals("-")) {
                out.add(a);
            }
        }
        return out;
    }

    /** For sed -i: drop the script (the first operand that is not a path). */
    private static List<String> files(List<String> operands) {
        return operands.isEmpty() || operands.get(0).startsWith("/") ? operands : operands.subList(1, operands.size());
    }

    private static String resolve(String base, String path) {
        return Paths.get(base).resolve(path).normalize().toString();
    }

    private static Path inOverlay(File overlay, String target) {
        // target is normalized and absolute, so this stays inside the overlay
        return overlay.toPath().resolve(target.substring(1)).normalize();
    }
}
