package app.plait.testclient;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/** A plain Shizuku client: uses only the public Shizuku API (and reflection for newProcess, as many real apps do). */
public class MainActivity extends Activity {

    private TextView log;
    private ScrollView logScroll;
    private TextView statusPill;
    private boolean autoStarted;

    // Material You colors from the system palette on Android 12+, fixed fallbacks before that
    private boolean dark;
    private int bg, surface, text, muted, primary, onPrimary, tonal, onTonal, good, bad;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadColors();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        int pad = dp(20);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(pad + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(),
                    pad + insets.getSystemWindowInsetRight(), pad + insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = label("Broker test client", 24, text, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);
        TextView subtitle = label("A plain Shizuku client: public API only", 14, muted, null);
        subtitle.setPadding(0, dp(2), 0, dp(14));
        root.addView(subtitle);

        statusPill = label("", 13, onTonal, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusPill.setPadding(dp(14), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.bottomMargin = dp(18);
        root.addView(statusPill, pillLp);

        root.addView(section("Access"));
        LinearLayout access = new LinearLayout(this);
        access.addView(button("Check status", true, v -> status()), weighted(dp(8)));
        access.addView(button("Request access", true, v -> requestPermission()), weighted(0));
        root.addView(access);

        root.addView(section("Commands"));
        root.addView(command("ip neigh show", () -> run("ip", "neigh", "show")));
        root.addView(command("id", () -> run("id")));
        root.addView(command("sh -c 'ls /data/local/tmp'", () -> run("sh", "-c", "ls /data/local/tmp")));
        root.addView(command("cat /proc/net/arp", () -> run("cat", "/proc/net/arp")));

        // Any command, sent the way most Shizuku apps do it: sh -c '<text>'
        LinearLayout custom = new LinearLayout(this);
        custom.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(this);
        input.setHint("command (sent as sh -c …)");
        input.setSingleLine(true);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(13);
        input.setTextColor(text);
        input.setHintTextColor(muted);
        input.setBackground(rounded(surface, dp(24)));
        input.setPadding(dp(18), 0, dp(18), 0);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        Runnable runInput = () -> {
            String script = input.getText().toString().trim();
            // The box already wraps the text in sh -c; typing it too would be a nested shell, which the broker refuses
            if (script.matches("(?s)sh -c\\s+.*")) {
                script = script.replaceFirst("^sh -c\\s+", "").trim();
                if (script.length() > 1 && (script.startsWith("'") && script.endsWith("'") || script.startsWith("\"") && script.endsWith("\""))) {
                    script = script.substring(1, script.length() - 1);
                }
            }
            if (!script.isEmpty()) run("sh", "-c", script);
        };
        input.setOnEditorActionListener((v, actionId, event) -> {
            runInput.run();
            return true;
        });
        custom.addView(input, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button go = button("Run", true, v -> runInput.run());
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        goLp.setMarginStart(dp(8));
        custom.addView(go, goLp);
        root.addView(custom);

        LinearLayout outHeader = new LinearLayout(this);
        outHeader.setGravity(Gravity.CENTER_VERTICAL);
        outHeader.addView(section("Output"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView clear = label("Clear", 14, primary, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clear.setPadding(dp(12), dp(14), dp(4), dp(6));
        clear.setOnClickListener(v -> log.setText(""));
        outHeader.addView(clear);
        root.addView(outHeader);

        log = label("", 12, text, Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setLineSpacing(0, 1.15f);
        log.setPadding(dp(14), dp(12), dp(14), dp(12));
        logScroll = new ScrollView(this);
        logScroll.setBackground(rounded(surface, dp(16)));
        logScroll.addView(log);
        root.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        lightSystemBars();
        refreshStatus();

        Shizuku.addBinderReceivedListenerSticky(() -> {
            append("binder received (server uid " + safeUid() + ")");
            refreshStatus();
            if (getIntent().getBooleanExtra("auto", false) && !autoStarted) {
                autoStarted = true;
                auto();
            } else if (getIntent().getBooleanExtra("installTest", false) && !autoStarted) {
                autoStarted = true;
                installTest();
            }
        });
        Shizuku.addBinderDeadListener(() -> {
            append("binder dead");
            refreshStatus();
        });
        Shizuku.addRequestPermissionResultListener((code, result) -> {
            append("permission result: " + (result == 0 ? "GRANTED" : "DENIED"));
            refreshStatus();
        });
    }

    // ---- look ----

    private void loadColors() {
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? sys(android.R.color.system_neutral1_900, 0xFF121316) : sys(android.R.color.system_neutral1_10, 0xFFFAF9FC);
        surface = dark ? sys(android.R.color.system_neutral2_800, 0xFF2A2B30) : sys(android.R.color.system_neutral2_50, 0xFFEEEFF4);
        text = dark ? sys(android.R.color.system_neutral1_50, 0xFFE3E2E6) : sys(android.R.color.system_neutral1_900, 0xFF1B1B1F);
        muted = dark ? sys(android.R.color.system_neutral2_200, 0xFFC4C6D0) : sys(android.R.color.system_neutral2_600, 0xFF5C5E67);
        primary = dark ? sys(android.R.color.system_accent1_200, 0xFFA8C7FA) : sys(android.R.color.system_accent1_600, 0xFF3A5BA9);
        onPrimary = dark ? sys(android.R.color.system_accent1_800, 0xFF0A305F) : 0xFFFFFFFF;
        tonal = dark ? sys(android.R.color.system_accent2_700, 0xFF3E4759) : sys(android.R.color.system_accent2_100, 0xFFDAE2F9);
        onTonal = dark ? sys(android.R.color.system_accent2_100, 0xFFDAE2F9) : sys(android.R.color.system_accent2_900, 0xFF131C2B);
        good = dark ? 0xFF7DD99A : 0xFF1B7A3D;
        bad = dark ? 0xFFFFB4AB : 0xFFBA1A1A;
    }

    private int sys(int res, int fallback) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? getColor(res) : fallback;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void lightSystemBars() {
        if (dark) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(light, light);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private TextView label(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        return t;
    }

    private TextView section(String s) {
        TextView t = label(s, 14, primary, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(4), dp(14), 0, dp(6));
        return t;
    }

    private LinearLayout.LayoutParams weighted(int endMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp.setMarginEnd(endMargin);
        return lp;
    }

    private Button button(String s, boolean filled, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(s);
        x.setAllCaps(false);
        x.setTextSize(15);
        x.setTextColor(filled ? onPrimary : onTonal);
        x.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        x.setStateListAnimator(null);
        x.setBackground(new RippleDrawable(ColorStateList.valueOf((filled ? onPrimary : onTonal) & 0x33FFFFFF),
                rounded(filled ? primary : tonal, dp(24)), null));
        x.setOnClickListener(l);
        return x;
    }

    private Button command(String cmd, Runnable r) {
        Button x = button("$ " + cmd, false, v -> r.run());
        x.setTypeface(Typeface.MONOSPACE);
        x.setTextSize(13);
        x.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        x.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.bottomMargin = dp(6);
        x.setLayoutParams(lp);
        return x;
    }

    private void refreshStatus() {
        String s;
        int color;
        if (!Shizuku.pingBinder()) {
            s = "●  Not connected to a broker";
            color = muted;
        } else {
            boolean granted;
            try {
                granted = Shizuku.checkSelfPermission() == 0;
            } catch (Throwable t) {
                granted = false;
            }
            s = "●  Connected · uid " + safeUid() + " · " + (granted ? "access granted" : "no access yet");
            color = granted ? good : primary;
        }
        String text = s;
        int dot = color;
        runOnUiThread(() -> {
            SpannableString span = new SpannableString(text);
            span.setSpan(new ForegroundColorSpan(dot), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            statusPill.setText(span);
            statusPill.setBackground(rounded(tonal, dp(16)));
        });
    }

    // ---- behavior (unchanged) ----

    private void append(String s) {
        android.util.Log.i("TC", s.replace("\n", " | "));
        SpannableString span = new SpannableString(s + "\n");
        int nl = s.indexOf('\n');
        if (s.startsWith("$ ")) {
            span.setSpan(new ForegroundColorSpan(primary), 0, nl < 0 ? s.length() : nl, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int err = s.indexOf("ERROR");
        if (err >= 0) span.setSpan(new ForegroundColorSpan(bad), err, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        runOnUiThread(() -> {
            log.append(span);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    /**
     * Scripted run for adb testing: ask once, then run the default commands, or each of the "scripts" extra as sh -c:
     * {@code am start -n app.plait.testclient/.MainActivity --ez auto true --esa scripts 'id,ls; reboot'}
     */
    private void auto() {
        append("AUTO start; ping=" + Shizuku.pingBinder());
        Shizuku.addRequestPermissionResultListener((code, result) -> {
            if (result == 0) runAll();
        });
        try {
            if (Shizuku.checkSelfPermission() == 0) runAll();
            else Shizuku.requestPermission(7);
        } catch (Throwable t) {
            append("AUTO error: " + t);
        }
    }

    private interface Args {
        void write(Parcel p);
    }

    /** A raw Binder call through the broker: what a Shizuku app does with ShizukuBinderWrapper. */
    private static Parcel call(IBinder target, String descriptor, int code, Args args) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            args.write(data);
            target.transact(code, data, reply, 0);
        } finally {
            data.recycle();
        }
        reply.readException();
        return reply;
    }

    /**
     * Exercises the broker's installer guard over adb. Transaction codes differ between Android releases, so they are passed in:
     * {@code am start -n app.plait.testclient/.MainActivity --ez installTest true --ei pmCode 136 --ei createCode 1
     *  --ei openCode 5 --ei abandonCode 4 --es pkg com.example.test}
     */
    private void installTest() {
        new Thread(() -> {
            Intent in = getIntent();
            String installerDesc = "android.content.pm.IPackageInstaller";
            try {
                IBinder pm = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package"));
                IBinder installer = new ShizukuBinderWrapper(call(pm, "android.content.pm.IPackageManager",
                        in.getIntExtra("pmCode", 0), p -> { }).readStrongBinder());

                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(in.getStringExtra("pkg"));
                Args create = p -> {
                    p.writeInt(1);
                    params.writeToParcel(p, 0);
                    p.writeString("com.android.vending"); // claims to be the Play Store
                    p.writeString(null);
                    p.writeInt(0);
                };
                int session = call(installer, installerDesc, in.getIntExtra("createCode", 0), create).readInt();
                append("INSTALLTEST created session " + session);

                expectRefused("other user", () -> call(installer, installerDesc, in.getIntExtra("createCode", 0), p -> {
                    p.writeInt(1);
                    params.writeToParcel(p, 0);
                    p.writeString(null);
                    p.writeString(null);
                    p.writeInt(10);
                }));
                expectRefused("open a session it did not create", () -> call(installer, installerDesc,
                        in.getIntExtra("openCode", 0), p -> p.writeInt(0x7ffffff0)));
                IBinder own = call(installer, installerDesc, in.getIntExtra("openCode", 0), p -> p.writeInt(session)).readStrongBinder();
                append("INSTALLTEST opened own session: " + (own != null));
                Thread.sleep(15000); // time for the adb side to inspect the session
                call(installer, installerDesc, in.getIntExtra("abandonCode", 0), p -> p.writeInt(session));
                append("INSTALLTEST abandoned own session");
            } catch (Throwable t) {
                append("INSTALLTEST ERROR " + t);
            }
        }).start();
    }

    private interface Call {
        void run() throws Exception;
    }

    private void expectRefused(String what, Call c) {
        try {
            c.run();
            append("INSTALLTEST NOT REFUSED: " + what);
        } catch (SecurityException e) {
            append("INSTALLTEST refused as expected (" + what + "): " + e.getMessage());
        } catch (Throwable t) {
            append("INSTALLTEST other error (" + what + "): " + t);
        }
    }

    private void runAll() {
        String[] args = getIntent().getStringArrayExtra("cmd"); // one command with exact arguments, no shell
        if (args != null) {
            run(args);
            return;
        }
        String[] scripts = getIntent().getStringArrayExtra("scripts");
        if (scripts != null) {
            for (String s : scripts) run("sh", "-c", s);
            return;
        }
        run("ip", "neigh", "show");
        run("id");
        run("sh", "-c", "ls /data/local/tmp");
        run("cat", "/proc/net/arp");
    }

    private String safeUid() {
        try {
            return String.valueOf(Shizuku.getUid());
        } catch (Throwable t) {
            return "?";
        }
    }

    private void status() {
        append("ping=" + Shizuku.pingBinder());
        refreshStatus();
        if (!Shizuku.pingBinder()) return;
        try {
            append("version=" + Shizuku.getVersion() + " uid=" + Shizuku.getUid() + " permission=" + Shizuku.checkSelfPermission());
        } catch (Throwable t) {
            append("status error: " + t);
        }
    }

    private void requestPermission() {
        try {
            if (Shizuku.checkSelfPermission() == 0) {
                append("already granted");
            } else {
                Shizuku.requestPermission(1);
                append("asked...");
            }
        } catch (Throwable t) {
            append("request error: " + t);
        }
    }

    private void run(String... cmd) {
        new Thread(() -> {
            try {
                Process p;
                if (getIntent().getBooleanExtra("local", false)) {
                    p = new ProcessBuilder(cmd).start(); // as this app itself, not through the broker (tests the su client)
                } else {
                    Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    m.setAccessible(true);
                    p = (Process) m.invoke(null, cmd, null, null);
                }
                String stdin = getIntent().getStringExtra("stdin");
                if (stdin != null && cmd[0].equals("plait-overlay")) {
                    p.getOutputStream().write(stdin.replace("\\n", "\n").getBytes());
                    p.getOutputStream().close();
                }
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                int n = 0;
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    if (n++ < 5) sb.append("  ").append(line).append('\n');
                }
                BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                while ((line = e.readLine()) != null) sb.append("  ! ").append(line).append('\n');
                int exit = p.waitFor();
                append("$ " + String.join(" ", cmd) + "\n  -> " + n + " lines, exit " + exit + "\n" + sb);
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                append("$ " + String.join(" ", cmd) + "\n  ERROR: " + c.getClass().getSimpleName() + ": " + c.getMessage());
            }
        }).start();
    }
}
