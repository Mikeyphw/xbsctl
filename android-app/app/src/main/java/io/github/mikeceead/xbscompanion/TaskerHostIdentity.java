package io.github.mikeceead.xbscompanion;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.security.MessageDigest;
import java.util.Locale;

/** Resolves and pins the Tasker host package and signing certificate. */
final class TaskerHostIdentity {
    static final String TASKER = "net.dinglisch.android.taskerm";
    static final String TASKER_LEGACY = "net.dinglisch.android.tasker";

    final String packageName;
    final String certificateSha256;
    final boolean valid;
    final String error;

    private TaskerHostIdentity(String packageName, String certificateSha256, boolean valid, String error) {
        this.packageName = clean(packageName);
        this.certificateSha256 = clean(certificateSha256).toLowerCase(Locale.US);
        this.valid = valid;
        this.error = clean(error);
    }

    static TaskerHostIdentity fromEditor(Activity activity) {
        ComponentName caller = activity == null ? null : activity.getCallingActivity();
        if (caller == null || !supportedPackage(caller.getPackageName())) return invalid("untrusted_editor");
        return installed(activity, caller.getPackageName());
    }

    static TaskerHostIdentity installed(Context context, String packageName) {
        if (context == null || !supportedPackage(packageName)) return invalid("unsupported_host_package");
        try {
            PackageManager pm = context.getPackageManager();
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null) return invalid("missing_signing_info");
                signatures = info.signingInfo.getApkContentsSigners();
            } else {
                @SuppressWarnings("deprecation") PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
                @SuppressWarnings("deprecation") Signature[] legacy = info.signatures;
                signatures = legacy;
            }
            if (signatures == null || signatures.length == 0) return invalid("missing_signing_certificate");
            return new TaskerHostIdentity(packageName, digest(signatures[0].toByteArray()), true, "");
        } catch (PackageManager.NameNotFoundException error) {
            return invalid("host_not_installed");
        } catch (Exception error) {
            return invalid("host_identity_error");
        }
    }

    static boolean supportedPackage(String packageName) {
        return TASKER.equals(packageName) || TASKER_LEGACY.equals(packageName);
    }

    boolean matches(String packageName, String certificate) {
        return valid && this.packageName.equals(clean(packageName))
            && constantEquals(this.certificateSha256, clean(certificate).toLowerCase(Locale.US));
    }

    private static TaskerHostIdentity invalid(String error) { return new TaskerHostIdentity("", "", false, error); }

    private static String digest(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest) out.append(String.format(Locale.US, "%02x", value & 0xff));
        return out.toString();
    }

    private static boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(clean(left).getBytes(java.nio.charset.StandardCharsets.UTF_8),
            clean(right).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
