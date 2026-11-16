package app.plait.broker.server;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The {@code su} command PlaitOS puts in {@code /system/bin}: {@code app_process ... SuClient [-c COMMAND]}. It runs as the
 * calling app and only talks to {@link SuServer}, which decides what each command may do.
 */
public final class SuClient {
    public static void main(String[] args) throws Exception {
        String command = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-c": case "--command":
                    if (i + 1 < args.length) command = args[++i];
                    break;
                case "-v": case "--version":
                    System.out.println("plait-fakeroot");
                    return;
                case "-V":
                    System.out.println(1);
                    return;
                default:
                    break; // "-", "root", --mount-master and so on: nothing to change
            }
        }
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(SuServer.PATH, LocalSocketAddress.Namespace.FILESYSTEM));
        } catch (Exception e) {
            System.err.println("su: Plait Broker is not running (" + e.getMessage() + ")");
            System.exit(1);
        }
        OutputStream out = s.getOutputStream();
        if (command != null) {
            out.write(("SU1 c\n" + command).getBytes("UTF-8"));
            s.shutdownOutput();
        } else {
            out.write("SU1 i\n".getBytes("UTF-8"));
            Thread t = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = System.in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        out.flush();
                    }
                    s.shutdownOutput();
                } catch (Exception ignored) {
                }
            });
            t.setDaemon(true);
            t.start();
        }
        DataInputStream in = new DataInputStream(s.getInputStream());
        int exit = 0;
        try {
            while (true) {
                int type = in.readUnsignedByte();
                int len = in.readInt();
                byte[] data = new byte[len];
                in.readFully(data);
                if (type == 1) {
                    System.out.write(data, 0, len);
                    System.out.flush();
                } else if (type == 2) {
                    System.err.write(data, 0, len);
                    System.err.flush();
                } else if (type == 3) {
                    exit = ((data[0] & 0xff) << 24) | ((data[1] & 0xff) << 16) | ((data[2] & 0xff) << 8) | (data[3] & 0xff);
                    break;
                }
            }
        } catch (EOFException ignored) {
        }
        System.exit(exit);
    }
}
