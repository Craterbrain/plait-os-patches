package app.plait.broker.server;


import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.content.IContentProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.DeviceIdleControllerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.UserManagerApis;
import rikka.hidden.compat.adapter.ProcessObserverAdapter;
import rikka.hidden.compat.adapter.UidObserverAdapter;
import rikka.shizuku.server.api.IContentProviderUtils;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.ShizukuApiConstants;
import moe.shizuku.api.BinderContainer;

/**
 * Delivers the daemon's Binder to apps that use the Shizuku client library. The library's ShizukuProvider
 * (authority "package.shizuku") receives a "sendBinder" call; only the shell or system may make it.
 * Adapted from Shizuku's BinderSender (Apache License 2.0).
 */
public final class BinderPusher {

    private static final Logger LOGGER = new Logger("BinderPusher");
    static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";
    private static BrokerService sService;

    private BinderPusher() {}

    private static class ProcessObserver extends ProcessObserverAdapter {
        private static final List<Integer> PID_LIST = new ArrayList<>();

        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) throws RemoteException {
            synchronized (PID_LIST) {
                if (PID_LIST.contains(pid) || !foregroundActivities) return;
                PID_LIST.add(pid);
            }
            sendForUid(uid);
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            synchronized (PID_LIST) {
                PID_LIST.remove((Integer) pid);
            }
        }

        @Override
        public void onProcessStateChanged(int pid, int uid, int procState) throws RemoteException {
            synchronized (PID_LIST) {
                if (PID_LIST.contains(pid)) return;
                PID_LIST.add(pid);
            }
            sendForUid(uid);
        }
    }

    private static class UidObserver extends UidObserverAdapter {
        private static final List<Integer> UID_LIST = new ArrayList<>();

        @Override
        public void onUidActive(int uid) throws RemoteException {
            starts(uid);
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) throws RemoteException {
            if (!cached) starts(uid);
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) throws RemoteException {
            starts(uid);
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            synchronized (UID_LIST) {
                UID_LIST.remove((Integer) uid);
            }
        }

        private void starts(int uid) throws RemoteException {
            synchronized (UID_LIST) {
                if (UID_LIST.contains(uid)) return;
                UID_LIST.add(uid);
            }
            sendForUid(uid);
        }
    }

    private static boolean requests(PackageInfo pi) {
        return pi != null && pi.requestedPermissions != null && Arrays.asList(pi.requestedPermissions).contains(PERMISSION);
    }

    private static void sendForUid(int uid) {
        List<String> packages = PackageManagerApis.getPackagesForUidNoThrow(uid);
        int userId = uid / 100000;
        for (String pkg : packages) {
            PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(pkg, PackageManager.GET_PERMISSIONS, userId);
            if (requests(pi)) {
                sendToApp(sService, pkg, userId, true);
                return;
            }
        }
    }

    /** Sends the binder to every installed app that asked for the Shizuku permission (called at start-up). */
    public static void sendToAllClients(Binder binder) {
        for (int userId : UserManagerApis.getUserIdsNoThrow()) {
            for (PackageInfo pi : PackageManagerApis.getInstalledPackagesNoThrow(PackageManager.GET_PERMISSIONS, userId)) {
                if (requests(pi)) sendToApp(binder, pi.packageName, userId, true);
            }
        }
    }

    static void sendToApp(Binder binder, String packageName, int userId, boolean retry) {
        try {
            DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(packageName, 30 * 1000, userId,
                    316 /* REASON_SHELL */, "shell");
        } catch (Throwable tr) {
            LOGGER.w(tr, "power whitelist failed for %s", packageName);
        }

        String name = packageName + ".shizuku";
        try {
            IContentProvider provider = ActivityManagerApis.getContentProviderExternal(name, userId, null, name);
            if (provider == null) {
                LOGGER.w("no provider %s in user %d", name, userId);
                return;
            }
            if (!provider.asBinder().pingBinder()) {
                if (retry) {
                    ActivityManagerApis.forceStopPackageNoThrow(packageName, userId);
                    Thread.sleep(1000);
                    sendToApp(binder, packageName, userId, false);
                }
                return;
            }
            Bundle extra = new Bundle();
            extra.putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER", new BinderContainer(binder));
            Bundle reply = IContentProviderUtils.callCompat(provider, null, name, "sendBinder", null, extra);
            LOGGER.i("binder %s to %s (user %d)", reply != null ? "delivered" : "NOT delivered", packageName, userId);
        } catch (Throwable tr) {
            LOGGER.w(tr, "failed to send binder to %s", packageName);
        }
    }

    public static void register(BrokerService service) {
        sService = service;
        try {
            ActivityManagerApis.registerProcessObserver(new ProcessObserver());
        } catch (Throwable tr) {
            LOGGER.e(tr, "registerProcessObserver");
        }
        // IActivityManager.UID_OBSERVER_* flags and PROCESS_STATE_UNKNOWN, written out because the stub class does not exist at runtime
        final int gone = 1 << 1, idle = 1 << 2, active = 1 << 3, cached = 1 << 4;
        int flags = gone | idle | active | cached;
        try {
            ActivityManagerApis.registerUidObserver(new UidObserver(), flags, -1 /* PROCESS_STATE_UNKNOWN */, null);
        } catch (Throwable tr) {
            LOGGER.e(tr, "registerUidObserver");
        }
    }
}
