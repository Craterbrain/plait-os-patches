package app.plait.broker;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;

/** Manager-side helper: custom transactions on the daemon's Binder (manager app only). */
final class BrokerClient {
    static final int TX_DUMP = 10010, TX_SET_MODE = 10011, TX_SET_CAP = 10012, TX_REMOVE = 10013, TX_ANSWER = 10014, TX_SET_GATES = 10015,
            TX_RESET_HOSTS = 10016, TX_SET_SHIZUKU_VISIBLE = 10017;

    private BrokerClient() {}

    static boolean alive() {
        return Shizuku.pingBinder();
    }

    private static Parcel call(int code, Object... args) throws RemoteException {
        IBinder binder = Shizuku.getBinder();
        if (binder == null || !binder.pingBinder()) throw new RemoteException("Broker daemon is not running");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
            for (Object a : args) {
                if (a instanceof Integer) data.writeInt((Integer) a);
                else data.writeString((String) a);
            }
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply;
        } finally {
            data.recycle();
        }
    }

    static String dump() throws RemoteException {
        Parcel r = call(TX_DUMP);
        try {
            return r.readString();
        } finally {
            r.recycle();
        }
    }

    static void setMode(int uid, String pkg, String mode) throws RemoteException {
        call(TX_SET_MODE, uid, pkg, mode).recycle();
    }

    static void setCap(int uid, String type, String key, boolean allowed) throws RemoteException {
        call(TX_SET_CAP, uid, type, key, allowed ? 1 : 0).recycle();
    }

    /** Whether this app sees the Shizuku placeholder (moe.shizuku.privileged.api) while it is allowed. */
    static void setShizukuVisible(int uid, boolean visible) throws RemoteException {
        call(TX_SET_SHIZUKU_VISIBLE, uid, visible ? 1 : 0).recycle();
    }

    static void remove(int uid) throws RemoteException {
        call(TX_REMOVE, uid).recycle();
    }

    /** Puts the phone's stock hosts file back (empties the DNS blocklist). */
    static void resetHosts() throws RemoteException {
        call(TX_RESET_HOSTS).recycle();
    }

    /** The master switch for gated functions (input injection, screen capture, ...). */
    static void setGatesEnabled(boolean enabled) throws RemoteException {
        call(TX_SET_GATES, enabled ? 1 : 0).recycle();
    }

    /** Answers a question the daemon is waiting on (InstallGuard.Asker values). */
    static void answer(int question, int answer) throws RemoteException {
        call(TX_ANSWER, question, answer).recycle();
    }
}
