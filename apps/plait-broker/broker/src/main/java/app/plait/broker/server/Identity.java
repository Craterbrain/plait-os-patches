package app.plait.broker.server;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import rikka.hidden.compat.PackageManagerApis;

/** Who an app really is: the SHA-256 of its signing certificate, which survives reinstalls and is not reused like uids. */
final class Identity {
    private Identity() {
    }

    /**
     * The app's certificate digest, or null if it cannot be read. With key rotation the oldest certificate in the history
     * is used, so a legitimate rotation keeps its policy; apps signed by several signers use all of them, sorted.
     */
    static String signingCert(String pkg, int userId) {
        PackageInfo pi = PackageManagerApis.getPackageInfoNoThrow(pkg, PackageManager.GET_SIGNING_CERTIFICATES, userId);
        SigningInfo si = pi != null ? pi.signingInfo : null;
        if (si == null) return null;
        Signature[] certs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
        if (certs == null || certs.length == 0) return null;
        List<String> digests = new ArrayList<>();
        if (si.hasMultipleSigners()) {
            for (Signature s : certs) digests.add(sha256(s.toByteArray()));
            Collections.sort(digests);
        } else {
            digests.add(sha256(certs[0].toByteArray()));
        }
        return String.join(",", digests);
    }

    private static String sha256(byte[] data) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
