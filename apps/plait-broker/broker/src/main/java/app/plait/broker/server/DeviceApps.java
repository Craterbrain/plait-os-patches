package app.plait.broker.server;

import android.content.pm.ProviderInfo;
import android.os.IBinder;

import java.util.List;
import java.util.Objects;

import rikka.hidden.compat.PackageManagerApis;

/** What {@link Classifier} needs to know about installed apps, answered by the package manager. Fails closed. */
final class DeviceApps implements Classifier.Apps {
    private final int userId;

    DeviceApps(int userId) {
        this.userId = userId;
    }

    @Override
    public int userId() {
        return userId;
    }

    @Override
    public boolean installedBy(String pkg, String installer) {
        try {
            IBinder binder = android.os.ServiceManager.getService("package");
            Object pm = Class.forName("android.content.pm.IPackageManager$Stub").getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            Object info = pm.getClass().getMethod("getInstallSourceInfo", String.class, int.class).invoke(pm, pkg, userId);
            return info != null && installer.equals(info.getClass().getMethod("getInstallingPackageName").invoke(info));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false; // unknown installer: not "installed by the caller"
        }
    }

    @Override
    public boolean sameSigner(String pkg, String other) {
        String a = Identity.signingCert(pkg, userId);
        return a != null && Objects.equals(a, Identity.signingCert(other, userId));
    }

    @Override
    public String packageOfUid(int uid) {
        List<String> pkgs = PackageManagerApis.getPackagesForUidNoThrow(uid);
        return pkgs.size() == 1 ? pkgs.get(0) : null;
    }

    @Override
    public String providerOwner(String authority) {
        try {
            ProviderInfo pi = PackageManagerApis.resolveContentProvider(authority, 0, userId);
            return pi != null ? pi.packageName : null;
        } catch (Exception e) {
            return null;
        }
    }
}
