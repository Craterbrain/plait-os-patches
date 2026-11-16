package app.plait.broker.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;

import org.junit.Test;

public class HostsFileTest {
    private static HostsFile.Filtered run(String content, StringWriter out) throws IOException {
        return HostsFile.filter(new StringReader(content), out);
    }

    @Test
    public void blockingEntriesAreKept() throws IOException {
        StringWriter out = new StringWriter();
        HostsFile.Filtered f = run("# list\n127.0.0.1 localhost\n0.0.0.0 ads.example.com tracker.example.com\n:: ads6.example.com\n", out);
        assertEquals(0, f.dropped);
        assertEquals(3, f.entries); // localhost is not counted
        assertTrue(out.toString().contains("0.0.0.0 ads.example.com tracker.example.com"));
        assertTrue(out.toString().contains("# list"));
    }

    @Test
    public void redirectingEntriesAreDropped() throws IOException {
        String text = HostsFile.filtered("0.0.0.0 ads.example.com\n1.2.3.4 yourbank.com\n10.0.0.1 router\n0.0.0.0 bad;name\n");
        assertFalse(text.contains("yourbank.com"));
        assertFalse(text.contains("router"));
        assertTrue(text.contains("ads.example.com"));
        assertEquals(3, run("0.0.0.0 a.example.com\n1.2.3.4 yourbank.com\n10.0.0.1 router\n0.0.0.0 bad;name\n", new StringWriter()).dropped);
    }

    @Test
    public void trailingCommentsAndWindowsLineEndingsAreHandled() throws IOException {
        StringWriter out = new StringWriter();
        assertEquals(0, run("0.0.0.0 a.example.com # ad\r\n0.0.0.0 b.example.com\r\n", out).dropped);
        assertTrue(out.toString().contains("0.0.0.0 a.example.com\n"));
    }

    @Test
    public void localhostAlwaysComesFirstOnce() throws IOException {
        String text = HostsFile.filtered("127.0.0.1 localhost\n0.0.0.0 a.example.com\n");
        assertTrue(text.startsWith("127.0.0.1 localhost\n"));
        assertEquals(text.indexOf("127.0.0.1 localhost"), text.lastIndexOf("127.0.0.1 localhost"));
    }

    @Test
    public void aMillionLineListStreams() throws IOException {
        int lines = 1_000_000;
        Reader big = new Reader() {
            private int i = 0;
            private String pending = "";

            @Override
            public int read(char[] buf, int off, int len) {
                if (pending.isEmpty()) {
                    if (i >= lines) return -1;
                    pending = "0.0.0.0 host" + (i++) + ".example.com\n";
                }
                int n = Math.min(len, pending.length());
                pending.getChars(0, n, buf, off);
                pending = pending.substring(n);
                return n;
            }

            @Override
            public void close() {
            }
        };
        long[] written = {0};
        java.io.Writer counting = new java.io.Writer() {
            @Override
            public void write(char[] c, int off, int len) {
                written[0] += len;
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        assertEquals(lines, HostsFile.filter(big, counting).entries);
        assertTrue(written[0] > lines * 20L);
    }

    @Test
    public void bothPathsOfTheHostsFileAreTheTarget() {
        assertTrue(HostsFile.isTarget("/system/etc/hosts"));
        assertTrue(HostsFile.isTarget("/etc/hosts"));
        assertFalse(HostsFile.isTarget("/system/etc/hosts.bak"));
    }

    @Test
    public void teeAndDdIntoTheSystemAreStdinWrites() {
        assertEquals(SystemWrites.Kind.STDIN, SystemWrites.plan(java.util.Arrays.asList("tee", "/system/etc/hosts"), "/").kind);
        assertEquals(SystemWrites.Kind.STDIN, SystemWrites.plan(java.util.Arrays.asList("dd", "of=/etc/hosts"), "/").kind);
        assertEquals(SystemWrites.Kind.NO_OP, SystemWrites.plan(java.util.Arrays.asList("dd", "if=/x", "of=/etc/hosts"), "/").kind);
    }
}
