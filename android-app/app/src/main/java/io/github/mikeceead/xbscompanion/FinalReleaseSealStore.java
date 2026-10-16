package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads build-generated host evidence and combines it with exact runtime APK checks. */
final class FinalReleaseSealStore {
    static final String ASSET_NAME = "phase31_release_evidence.properties";
    private FinalReleaseSealStore() {}

    static FinalReleaseSealReport evaluate(Context context) {
        Context app = context.getApplicationContext();
        FinalReleaseSealPolicy.Evidence evidence = loadHostEvidence(app);
        evidence.evaluatedAt = System.currentTimeMillis();
        evidence.versionName = BuildConfig.VERSION_NAME;
        evidence.versionCode = BuildConfig.VERSION_CODE;
        RuntimeCheck signing = signingCheck(app);
        evidence.signedApkReady = signing.passed;
        evidence.signedApkDetail = signing.detail;
        evidence.signedApkCapturedAt = signing.capturedAt;
        RuntimeCheck profile = packagedProfileCheck(app, evidence.expectedPackagedProfileSha256,
            evidence.expectedPackagedProfileMetadataSha256);
        evidence.packagedBaselineProfileReady = profile.passed;
        evidence.packagedBaselineProfileDetail = profile.detail;
        evidence.packagedBaselineProfileCapturedAt = profile.capturedAt;
        return FinalReleaseSealPolicy.evaluate(evidence);
    }

    private static FinalReleaseSealPolicy.Evidence loadHostEvidence(Context context) {
        FinalReleaseSealPolicy.Evidence evidence = new FinalReleaseSealPolicy.Evidence();
        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(
            context.getAssets().open(ASSET_NAME), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (Exception error) {
            evidence.evidenceError = "build-generated evidence asset missing or unreadable";
            return evidence;
        }
        if (!"2".equals(properties.getProperty("format", ""))) {
            evidence.evidenceError = "unsupported evidence format";
            return evidence;
        }
        Set<String> expectedKeys = expectedPropertyKeys();
        if (!properties.stringPropertyNames().equals(expectedKeys)) {
            Set<String> missing = new HashSet<>(expectedKeys);
            missing.removeAll(properties.stringPropertyNames());
            Set<String> extra = new HashSet<>(properties.stringPropertyNames());
            extra.removeAll(expectedKeys);
            evidence.evidenceError = "bundled evidence property set mismatch missing=" + missing + " extra=" + extra;
            return evidence;
        }
        evidence.sessionId = properties.getProperty("session_id", "").trim();
        evidence.sessionStartedAt = number(properties.getProperty("session_started_at"));
        evidence.capturedAt = number(properties.getProperty("captured_at"));
        evidence.evidenceVersionName = properties.getProperty("app_version_name", "").trim();
        evidence.evidenceVersionCode = number(properties.getProperty("app_version_code"));
        evidence.sourceSealSha256 = normalizeHex(properties.getProperty("source_seal_sha256", ""));
        evidence.promiseSealSha256 = normalizeHex(properties.getProperty("promise_seal_sha256", ""));
        evidence.candidateReceiptSha256 = normalizeHex(properties.getProperty("candidate_receipt_sha256", ""));
        evidence.hostEvidenceSha256 = normalizeHex(properties.getProperty("host_evidence_sha256", ""));
        evidence.expectedPackagedProfileSha256 = normalizeHex(
            properties.getProperty("expected_packaged_profile_sha256", ""));
        evidence.expectedPackagedProfileMetadataSha256 = normalizeHex(
            properties.getProperty("expected_packaged_profile_metadata_sha256", ""));
        for (String id : FinalReleaseSealPolicy.hostGateIds()) {
            String prefix = "gate." + id + ".";
            boolean passed = "PASS".equals(properties.getProperty(prefix + "status", ""));
            String detail = properties.getProperty(prefix + "detail", "");
            long capturedAt = number(properties.getProperty(prefix + "captured_at"));
            evidence.hostGates.put(id, new FinalReleaseSealPolicy.HostGate(passed, detail, capturedAt));
        }
        return evidence;
    }

    private static Set<String> expectedPropertyKeys() {
        Set<String> keys = new HashSet<>();
        keys.add("format");
        keys.add("session_id");
        keys.add("session_started_at");
        keys.add("captured_at");
        keys.add("app_version_name");
        keys.add("app_version_code");
        keys.add("source_seal_sha256");
        keys.add("promise_seal_sha256");
        keys.add("candidate_receipt_sha256");
        keys.add("expected_packaged_profile_sha256");
        keys.add("expected_packaged_profile_metadata_sha256");
        keys.add("host_evidence_sha256");
        for (String id : FinalReleaseSealPolicy.hostGateIds()) {
            String prefix = "gate." + id + ".";
            keys.add(prefix + "status");
            keys.add(prefix + "captured_at");
            keys.add(prefix + "detail");
        }
        return keys;
    }

    private static RuntimeCheck signingCheck(Context context) {
        long capturedAt = System.currentTimeMillis();
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return RuntimeCheck.blocked("Installed package is debuggable", capturedAt);
        }
        String expected = normalizeHex(BuildConfig.PHASE31_RELEASE_CERT_SHA256);
        if (expected.length() != 64) {
            return RuntimeCheck.blocked("Expected release certificate fingerprint was not embedded", capturedAt);
        }
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info;
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= 28) {
                info = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null) return RuntimeCheck.blocked("SigningInfo unavailable", capturedAt);
                signatures = info.signingInfo.getApkContentsSigners();
            } else {
                info = legacyPackageInfo(manager, context.getPackageName());
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length != 1) {
                return RuntimeCheck.blocked("Installed APK current signer set is not exactly one certificate", capturedAt);
            }
            String actual = sha256(signatures[0].toByteArray());
            if (!expected.equals(actual)) {
                return RuntimeCheck.blocked("Installed APK exact current signer does not match the release fingerprint", capturedAt);
            }
            return RuntimeCheck.pass("Installed APK has exactly one current signer and it matches the release fingerprint", capturedAt);
        } catch (Exception error) {
            return RuntimeCheck.blocked("Unable to inspect installed APK signature: "
                + error.getClass().getSimpleName(), capturedAt);
        }
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo legacyPackageInfo(PackageManager manager, String packageName)
        throws PackageManager.NameNotFoundException {
        return manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
    }

    private static RuntimeCheck packagedProfileCheck(Context context, String expectedProfile,
                                                       String expectedMetadata) {
        long capturedAt = System.currentTimeMillis();
        if (expectedProfile.length() != 64 || expectedMetadata.length() != 64) {
            return RuntimeCheck.blocked("Expected packaged profile hashes are missing", capturedAt);
        }
        try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
            byte[] profile = readEntry(apk, "assets/dexopt/baseline.prof", 32, 1_500_000);
            byte[] metadata = readEntry(apk, "assets/dexopt/baseline.profm", 8, 1_500_000);
            if (!hasMagic(profile, 'p', 'r', 'o') || !hasMagic(metadata, 'p', 'r', 'm')) {
                return RuntimeCheck.blocked("Packaged Baseline Profile or metadata has invalid ART magic", capturedAt);
            }
            String profileHash = sha256(profile);
            String metadataHash = sha256(metadata);
            if (!expectedProfile.equals(profileHash) || !expectedMetadata.equals(metadataHash)) {
                return RuntimeCheck.blocked("Packaged Baseline Profile differs from the clean candidate profile", capturedAt);
            }
            return RuntimeCheck.pass("Installed APK profile and metadata exactly match the clean candidate hashes", capturedAt);
        } catch (Exception error) {
            return RuntimeCheck.blocked("Unable to verify installed APK profile: "
                + error.getClass().getSimpleName(), capturedAt);
        }
    }

    private static boolean hasMagic(byte[] bytes, char first, char second, char third) {
        return bytes.length >= 4 && bytes[0] == first && bytes[1] == second && bytes[2] == third && bytes[3] == 0;
    }

    private static byte[] readEntry(ZipFile apk, String name, int minimum, int maximum) throws Exception {
        ZipEntry entry = apk.getEntry(name);
        if (entry == null || entry.getSize() < minimum || entry.getSize() > maximum) {
            throw new IllegalStateException("Invalid APK entry " + name);
        }
        try (InputStream stream = apk.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int)entry.getSize())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte value : digest) out.append(String.format(Locale.US, "%02x", value & 0xff));
        return out.toString();
    }

    private static String normalizeHex(String value) {
        return value == null ? "" : value.replaceAll("[^A-Fa-f0-9]", "").toLowerCase(Locale.US);
    }

    private static long number(String value) {
        try { return Long.parseLong(value == null ? "" : value.trim()); }
        catch (RuntimeException ignored) { return 0L; }
    }

    private static final class RuntimeCheck {
        final boolean passed;
        final String detail;
        final long capturedAt;
        RuntimeCheck(boolean passed, String detail, long capturedAt) {
            this.passed = passed;
            this.detail = detail;
            this.capturedAt = capturedAt;
        }
        static RuntimeCheck pass(String detail, long capturedAt) {
            return new RuntimeCheck(true, detail, capturedAt);
        }
        static RuntimeCheck blocked(String detail, long capturedAt) {
            return new RuntimeCheck(false, detail, capturedAt);
        }
    }
}
