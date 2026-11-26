package app.plait.broker.server;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

import rikka.shizuku.server.util.Logger;

/**
 * PlaitOS ships a code-less placeholder app named moe.shizuku.privileged.api, because apps like Aurora Store only offer their Shizuku
 * features when Shizuku's own package is installed. A framework patch (PlaitShizukuVisibility) hides it from every app except the ones in
 * the setting written here: the apps that were authorized in the Broker, whether they asked or were added by hand.
 */
final class ShizukuPlaceholder {
    static final String SETTING = "plait_shizuku_visible_uids";
    private static final Logger LOGGER = new Logger("ShizukuPlaceholder");
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "shizuku-placeholder");
        t.setDaemon(true);
        return t;
    });

    // Guarded by ShizukuPlaceholder.class.
    private static String desired;
    private static String published;
    private static boolean scheduled;

    private ShizukuPlaceholder() {
    }

    /** Queues the latest full UID list ("10234,1010234"; "-" for none). */
    static synchronized void publish(String uids) {
        desired = uids.isEmpty() ? "-" : uids;
        schedule(0);
    }

    private static void schedule(long delaySeconds) {
        if (!scheduled && !desired.equals(published)) {
            scheduled = true;
            WRITER.schedule(ShizukuPlaceholder::write, delaySeconds, TimeUnit.SECONDS);
        }
    }

    private static void write() {
        final String value;
        synchronized (ShizukuPlaceholder.class) {
            value = desired;
        }
        boolean success = false;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"settings", "put", "global", SETTING, value});
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                int code = process.exitValue();
                if (code == 0) {
                    // SettingsService can return success even when the provider rejects the write.
                    process = Runtime.getRuntime().exec(new String[]{"settings", "get", "global", SETTING});
                    if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                        String actual = new String(process.getInputStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8).trim();
                        success = value.equals(actual);
                    }
                    if (!success) LOGGER.w("could not confirm write to %s", SETTING);
                } else {
                    LOGGER.w("settings put %s exited with %d", SETTING, code);
                }
            } else {
                LOGGER.w("timed out publishing %s", SETTING);
            }
        } catch (java.io.IOException | InterruptedException e) {
            LOGGER.w(e, "could not publish %s", SETTING);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            synchronized (ShizukuPlaceholder.class) {
                published = success ? value : null;
                scheduled = false;
                schedule(success ? 0 : 2);
            }
        }
    }
}
