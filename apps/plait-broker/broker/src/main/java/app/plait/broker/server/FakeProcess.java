package app.plait.broker.server;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * An already finished process with fixed output, for commands the broker answers itself instead of running: refusals
 * (so an app sees a failed command, as from any shell, rather than an exception it may not catch) and pretended
 * system writes.
 */
final class FakeProcess extends Process {
    private final int exitCode;
    private final byte[] out;
    private final byte[] err;

    FakeProcess(int exitCode, String out, String err) {
        this.exitCode = exitCode;
        this.out = out.getBytes(StandardCharsets.UTF_8);
        this.err = err.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public OutputStream getOutputStream() {
        return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(out);
    }

    @Override
    public InputStream getErrorStream() {
        return new ByteArrayInputStream(err);
    }

    @Override
    public int waitFor() {
        return exitCode;
    }

    @Override
    public int exitValue() {
        return exitCode;
    }

    @Override
    public boolean isAlive() {
        return false;
    }

    @Override
    public void destroy() {
    }
}
