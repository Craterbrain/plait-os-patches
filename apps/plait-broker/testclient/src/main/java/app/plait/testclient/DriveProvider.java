package app.plait.testclient;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Drives the test suite over adb without opening the app, so it does not get in the way of what is on the screen:
 *
 * <pre>
 * adb shell content call --uri content://app.plait.testclient.drive --method list
 * adb shell content call --uri content://app.plait.testclient.drive --method run --arg all [--extra wait:i:120]
 * adb shell content call --uri content://app.plait.testclient.drive --method results
 * adb shell content call --uri content://app.plait.testclient.drive --method exec --arg 'pm list packages' --extra mode:s:su
 * </pre>
 *
 * Only the shell user may call it (the DUMP permission). The broker can still pop up its own questions; those are the
 * broker's, and wait for you.
 */
public class DriveProvider extends ContentProvider {
    private static Suite suite;

    private static synchronized Suite suite(android.content.Context c) {
        if (suite == null) suite = new Suite(c);
        return suite;
    }

    /** The activity and the provider share one suite, so what runs headless shows on screen. */
    static Suite shared(android.content.Context c) {
        return suite(c);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Suite s = suite(getContext());
        Bundle out = new Bundle();
        try {
            switch (method) {
                case "list": {
                    StringBuilder b = new StringBuilder();
                    for (Suite.Test t : s.tests) b.append(t.group).append(" | ").append(t.id).append(" | ").append(t.title).append('\n');
                    out.putString("tests", b.toString());
                    break;
                }
                case "run": {
                    String what = arg == null || arg.isEmpty() ? "all" : arg;
                    java.util.List<Suite.Test> which = s.select(what);
                    if (which.isEmpty()) {
                        out.putString("error", "nothing matches '" + what + "' (a test id, a group, or all)");
                        break;
                    }
                    if (!s.start(which)) {
                        out.putString("error", "a run is already going; use --method results");
                        out.putString("report", s.report());
                        break;
                    }
                    int wait = extras != null ? extras.getInt("wait", 120) : 120;
                    s.await(wait);
                    out.putBoolean("finished", !s.running());
                    out.putString("report", s.report());
                    break;
                }
                case "results":
                    out.putBoolean("running", s.running());
                    out.putString("report", s.report());
                    break;
                case "exec": {
                    String mode = extras != null ? extras.getString("mode", "broker") : "broker";
                    String stdin = extras != null ? extras.getString("stdin") : null;
                    Suite.Env env = new Suite.Env(getContext());
                    if (arg == null || arg.isEmpty()) {
                        out.putString("error", "give the command as --arg");
                        break;
                    }
                    Suite.Res r;
                    if (mode.equals("su")) {
                        r = env.su(arg);
                    } else {
                        if (!env.binder(8000)) {
                            out.putString("error", "no broker connection");
                            break;
                        }
                        r = env.broker(stdin == null ? null : stdin.replace("\\n", "\n"), "sh", "-c", arg);
                    }
                    out.putInt("exit", r.exit);
                    out.putString("out", r.out);
                    out.putString("err", r.err);
                    break;
                }
                case "reset":
                    suite = null;
                    break;
                default:
                    out.putString("error", "methods: list, run, results, exec, reset");
            }
        } catch (Throwable t) {
            out.putString("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
