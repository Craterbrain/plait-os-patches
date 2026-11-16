package app.plait.broker.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SystemWritesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static SystemWrites.Plan plan(String... argv) {
        return SystemWrites.plan(Arrays.asList(argv), "/");
    }

    private static void assertKind(SystemWrites.Kind kind, List<String> targets, SystemWrites.Plan p) {
        assertEquals(kind, p.kind);
        assertEquals(targets, p.targets);
    }

    @Test
    public void remountingSystemIsPretended() {
        assertKind(SystemWrites.Kind.REMOUNT, List.of("/system"), plan("mount", "-o", "rw,remount", "/system"));
        assertKind(SystemWrites.Kind.REMOUNT, List.of("/"), plan("mount", "-o", "remount,rw", "/"));
        assertKind(SystemWrites.Kind.REMOUNT, List.of("/vendor"), plan("/system/bin/mount", "-orw,remount", "/vendor"));
        assertNull(plan("mount", "-o", "rw,remount", "/data"));
        assertNull(plan("mount", "-t", "tmpfs", "tmpfs", "/system"));
    }

    @Test
    public void writesIntoSystemPathsArePlanned() {
        assertKind(SystemWrites.Kind.COPY, List.of("/system/etc/hosts"), plan("cp", "/sdcard/hosts", "/system/etc/hosts"));
        assertKind(SystemWrites.Kind.COPY, List.of("/system/etc/hosts"), plan("cp", "-f", "/sdcard/hosts", "/etc/../system/etc/hosts"));
        assertKind(SystemWrites.Kind.COPY, List.of("/etc/hosts"), plan("mv", "/sdcard/hosts", "/etc/hosts"));
        assertKind(SystemWrites.Kind.NO_OP, List.of("/system/app/Foo"), plan("rm", "-rf", "/system/app/Foo"));
        assertKind(SystemWrites.Kind.NO_OP, List.of("/system/bin/x"), plan("chmod", "755", "/system/bin/x"));
        assertKind(SystemWrites.Kind.NO_OP, List.of("/system/build.prop"), plan("sed", "-i", "s/a/b/", "/system/build.prop"));
        assertKind(SystemWrites.Kind.NO_OP, List.of("/vendor/x.img"), plan("dd", "if=/sdcard/x", "of=/vendor/x.img"));
        assertKind(SystemWrites.Kind.CREATE_DIR, List.of("/product/overlay/Mine"), plan("mkdir", "-p", "/product/overlay/Mine"));
        assertKind(SystemWrites.Kind.CREATE_FILE, List.of("/system/.touched"), plan("touch", "/system/.touched"));
    }

    @Test
    public void anythingElseRunsForReal() {
        assertNull(plan("cp", "/system/etc/hosts", "/sdcard/hosts"));       // reading the system is fine
        assertNull(plan("rm", "/system/app/Foo", "/sdcard/foo"));            // mixed: runs, fails on the system part
        assertNull(plan("rm", "/system/../data/local/tmp/x"));               // normalizes out of /system
        assertNull(plan("chmod", "755", "/data/local/tmp/x"));
        assertNull(plan("ls", "/system"));
        assertNull(plan("sed", "s/a/b/", "/system/build.prop"));             // no -i: only prints
        assertNull(plan("touch"));
    }

    @Test
    public void relativePathsUseTheWorkingDirectory() {
        assertKind(SystemWrites.Kind.NO_OP, List.of("/system/etc/hosts"),
                SystemWrites.plan(List.of("rm", "hosts"), "/system/etc"));
        assertNull(SystemWrites.plan(List.of("rm", "hosts"), "/data/local/tmp"));
    }

    @Test
    public void copiesAreKeptInTheAppsOverlay() throws Exception {
        File src = tmp.newFile("hosts");
        Files.write(src.toPath(), "127.0.0.1 ads.example\n".getBytes(StandardCharsets.UTF_8));
        File overlay = tmp.newFolder("overlay");

        assertNull(SystemWrites.apply(plan("cp", src.getPath(), "/system/etc/hosts"), overlay));
        assertArrayEquals(Files.readAllBytes(src.toPath()), Files.readAllBytes(new File(overlay, "system/etc/hosts").toPath()));

        // a trailing '/' means "into this directory", even though paths are normalized
        assertNull(SystemWrites.apply(plan("cp", src.getPath(), "/system/fonts/"), overlay));
        assertTrue(new File(overlay, "system/fonts/hosts").isFile());

        assertNull(SystemWrites.apply(plan("mkdir", "-p", "/product/overlay/Mine"), overlay));
        assertTrue(new File(overlay, "product/overlay/Mine").isDirectory());
        assertNull(SystemWrites.apply(plan("rm", "-rf", "/system/app"), overlay));
        assertTrue(new File(overlay, "system/etc/hosts").isFile()); // removals change nothing

        assertEquals("cp: /system/etc/hosts: Read-only file system\n",
                SystemWrites.readOnlyError(List.of("cp", "x", "/system/etc/hosts"), plan("cp", "x", "/system/etc/hosts")));
    }

    @Test
    public void unreadableSourcesAreReportedNotFatal() throws Exception {
        File overlay = tmp.newFolder("overlay");
        String note = SystemWrites.apply(plan("cp", "/nonexistent/file", "/system/etc/hosts"), overlay);
        assertTrue(note.startsWith("not a readable file"));
        assertFalse(new File(overlay, "system/etc/hosts").exists());
    }
}
