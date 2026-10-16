package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Android-Keystore-backed HMAC signing for bounded Tasker configurations. */
final class TaskerAuthorization {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "xbs_tasker_config_hmac_v2";
    private static final SecureRandom RANDOM = new SecureRandom();
    private TaskerAuthorization() {}

    static TaskerHostIdentity editorIdentity(android.app.Activity activity) { return TaskerHostIdentity.fromEditor(activity); }

    static boolean sign(Context context, Bundle bundle, TaskerHostIdentity host) {
        if (bundle == null || host == null || !host.valid) return false;
        bundle.putString(TaskerRequestPolicy.KEY_VERSION, String.valueOf(TaskerRequestPolicy.VERSION));
        bundle.putString(TaskerRequestPolicy.KEY_HOST_PACKAGE, host.packageName);
        bundle.putString(TaskerRequestPolicy.KEY_HOST_CERT, host.certificateSha256);
        if (bundle.getString(TaskerRequestPolicy.KEY_NONCE, "").isEmpty()) bundle.putString(TaskerRequestPolicy.KEY_NONCE, nonce());
        java.util.Set<String> allowed = TaskerRequestPolicy.actionKeys();
        if (TaskerConditionProtocol.KIND_EVENT.equals(bundle.getString(TaskerConditionProtocol.KEY_KIND, ""))
            || TaskerConditionProtocol.KIND_STATE.equals(bundle.getString(TaskerConditionProtocol.KEY_KIND, ""))) {
            allowed = TaskerRequestPolicy.conditionKeys();
        }
        if (!TaskerConfigCodec.validTypes(bundle, allowed)) return false;
        String signature = TaskerRequestPolicy.sign(TaskerConfigCodec.strings(bundle), key(true));
        if (signature.isEmpty()) return false;
        bundle.putString(TaskerRequestPolicy.KEY_SIGNATURE, signature);
        return rememberHost(context, host);
    }

    static TaskerRequestPolicy.Validation verify(Context context, Bundle bundle, boolean condition) {
        if (bundle == null) return TaskerRequestPolicy.Validation.reject("missing_config");
        java.util.Set<String> allowed = condition ? TaskerRequestPolicy.conditionKeys() : TaskerRequestPolicy.actionKeys();
        if (!TaskerConfigCodec.validTypes(bundle, allowed)) return TaskerRequestPolicy.Validation.reject("invalid_bundle_type");
        SecretKey key = key(false);
        if (key == null) return TaskerRequestPolicy.Validation.reject("signing_key_unavailable");
        TaskerRequestPolicy.Validation result = condition
            ? TaskerRequestPolicy.validateCondition(TaskerConfigCodec.strings(bundle))
            : TaskerRequestPolicy.validateAction(TaskerConfigCodec.strings(bundle), java.util.Collections.emptySet(), false);
        if (!result.valid) return result;
        result = TaskerRequestPolicy.verify(TaskerConfigCodec.strings(bundle), allowed, key);
        if (!result.valid) return result;
        TaskerHostIdentity installed = TaskerHostIdentity.installed(context, bundle.getString(TaskerRequestPolicy.KEY_HOST_PACKAGE, ""));
        if (!installed.matches(bundle.getString(TaskerRequestPolicy.KEY_HOST_PACKAGE, ""), bundle.getString(TaskerRequestPolicy.KEY_HOST_CERT, ""))) {
            return TaskerRequestPolicy.Validation.reject("host_identity_changed");
        }
        return result;
    }

    static boolean trustedHost(Context context, String packageName) {
        if (context == null || !TaskerHostIdentity.supportedPackage(packageName)) return false;
        android.content.SharedPreferences prefs = context.getApplicationContext()
            .getSharedPreferences("tasker_host_pin_v2", Context.MODE_PRIVATE);
        String pinnedPackage = prefs.getString("package", "");
        String pinnedCertificate = prefs.getString("certificate", "");
        TaskerHostIdentity installed = TaskerHostIdentity.installed(context, packageName);
        return installed.matches(pinnedPackage, pinnedCertificate);
    }


    static HostPinStatus pinStatus(Context context) {
        if (context == null) return new HostPinStatus(false, false, "", "Context unavailable");
        android.content.SharedPreferences prefs = context.getApplicationContext().getSharedPreferences("tasker_host_pin_v2", Context.MODE_PRIVATE);
        String packageName = prefs.getString("package", ""); String certificate = prefs.getString("certificate", "");
        if (packageName.isEmpty() && certificate.isEmpty()) return new HostPinStatus(true, false, "", "No Tasker host is configured");
        if (!TaskerHostIdentity.supportedPackage(packageName) || certificate.length() != 64) return new HostPinStatus(false, true, packageName, "Pinned Tasker identity is incomplete");
        TaskerHostIdentity installed = TaskerHostIdentity.installed(context, packageName);
        boolean ready = installed.matches(packageName, certificate);
        String detail = ready ? "Pinned Tasker signer verified · " + certificate.substring(0, 12) : "Installed Tasker signer does not match the pin";
        return new HostPinStatus(ready, true, packageName, detail);
    }

    static final class HostPinStatus {
        final boolean ready,configured; final String packageName,detail;
        HostPinStatus(boolean r,boolean c,String p,String d){ready=r;configured=c;packageName=p==null?"":p;detail=d==null?"":d;}
    }

    private static boolean rememberHost(Context context, TaskerHostIdentity host) {
        if (context == null || host == null || !host.valid) return false;
        return context.getApplicationContext().getSharedPreferences("tasker_host_pin_v2", Context.MODE_PRIVATE)
            .edit().putString("package", host.packageName).putString("certificate", host.certificateSha256).commit();
    }

    private static SecretKey key(boolean create) {
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE); store.load(null);
            java.security.Key existing = store.getKey(ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey)existing;
            if (!create) return null;
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build());
            return generator.generateKey();
        } catch (Exception error) { return null; }
    }

    private static String nonce() { byte[] bytes=new byte[18];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
