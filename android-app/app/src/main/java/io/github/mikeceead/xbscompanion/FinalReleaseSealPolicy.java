package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Pure-Java fail-closed policy for fresh, session-bound Phase 31 release evidence. */
final class FinalReleaseSealPolicy {
    static final String JVM_TESTS = "jvm_tests";
    static final String RELEASE_LINT = "release_lint";
    static final String SIGNED_APK = "signed_apk";
    static final String ANDROID_INSTRUMENTATION = "android_instrumentation";
    static final String ROOM_MIGRATION = "room_migration";
    static final String ACCESSIBILITY = "accessibility";
    static final String MERGED_MANIFEST = "merged_manifest";
    static final String MACROBENCHMARK = "macrobenchmark";
    static final String GENERATED_BASELINE_PROFILE = "generated_baseline_profile";
    static final String PACKAGED_BASELINE_PROFILE = "packaged_baseline_profile";
    static final String GRADLE_TESTKIT = "gradle_testkit";
    static final String ARTIFACT_REPLAY = "artifact_replay";
    static final String GIT_FINALIZATION = "git_finalization";
    static final String SOURCE_PROMISE_SEAL = "source_promise_seal";

    static final long MAX_EVIDENCE_AGE_MS = 6L * 60L * 60L * 1000L;
    static final long MAX_FUTURE_SKEW_MS = 5L * 60L * 1000L;

    private static final Map<String, String> TITLES;
    static {
        LinkedHashMap<String, String> titles = new LinkedHashMap<>();
        titles.put(JVM_TESTS, "JVM and repository regression suite");
        titles.put(RELEASE_LINT, "Release lint");
        titles.put(SIGNED_APK, "Signed APK identity");
        titles.put(ANDROID_INSTRUMENTATION, "Android instrumentation");
        titles.put(ROOM_MIGRATION, "Room v1-to-v2 migration");
        titles.put(ACCESSIBILITY, "Espresso accessibility checks");
        titles.put(MERGED_MANIFEST, "Merged-manifest security");
        titles.put(MACROBENCHMARK, "Macrobenchmark evidence");
        titles.put(GENERATED_BASELINE_PROFILE, "Generated Baseline Profile");
        titles.put(PACKAGED_BASELINE_PROFILE, "Packaged Baseline Profile");
        titles.put(GRADLE_TESTKIT, "Gradle TestKit graph contract");
        titles.put(ARTIFACT_REPLAY, "Exact complete-source artifact replay");
        titles.put(GIT_FINALIZATION, "Clean exact-source Git finalization");
        titles.put(SOURCE_PROMISE_SEAL, "Source and promise audit");
        TITLES = Collections.unmodifiableMap(titles);
    }

    static final class HostGate {
        final boolean passed;
        final String detail;
        final long capturedAt;
        HostGate(boolean passed, String detail, long capturedAt) {
            this.passed = passed;
            this.detail = clean(detail);
            this.capturedAt = Math.max(0L, capturedAt);
        }
    }

    static final class Evidence {
        long evaluatedAt;
        long sessionStartedAt;
        long capturedAt;
        String sessionId = "";
        String versionName = "";
        long versionCode;
        String evidenceVersionName = "";
        long evidenceVersionCode;
        String sourceSealSha256 = "";
        String promiseSealSha256 = "";
        String candidateReceiptSha256 = "";
        String hostEvidenceSha256 = "";
        String expectedPackagedProfileSha256 = "";
        String expectedPackagedProfileMetadataSha256 = "";
        String evidenceError = "";
        final Map<String, HostGate> hostGates = new LinkedHashMap<>();
        boolean signedApkReady;
        String signedApkDetail = "";
        long signedApkCapturedAt;
        boolean packagedBaselineProfileReady;
        String packagedBaselineProfileDetail = "";
        long packagedBaselineProfileCapturedAt;
    }

    private FinalReleaseSealPolicy() {}

    static List<String> gateIds() { return new ArrayList<>(TITLES.keySet()); }
    static List<String> hostGateIds() {
        ArrayList<String> ids = new ArrayList<>(TITLES.keySet());
        ids.remove(SIGNED_APK);
        ids.remove(PACKAGED_BASELINE_PROFILE);
        return ids;
    }

    static FinalReleaseSealReport evaluate(Evidence raw) {
        Evidence evidence = raw == null ? new Evidence() : raw;
        long now = evidence.evaluatedAt > 0L ? evidence.evaluatedAt : System.currentTimeMillis();
        boolean envelopeReady = clean(evidence.evidenceError).isEmpty()
            && hex64(evidence.sessionId)
            && fresh(evidence.sessionStartedAt, evidence.sessionStartedAt, now)
            && fresh(evidence.capturedAt, evidence.sessionStartedAt, now)
            && evidence.capturedAt >= evidence.sessionStartedAt
            && evidence.versionCode == 15L
            && evidence.versionCode == evidence.evidenceVersionCode
            && "4.2.0".equals(clean(evidence.versionName))
            && clean(evidence.versionName).equals(clean(evidence.evidenceVersionName))
            && hex64(evidence.sourceSealSha256)
            && hex64(evidence.promiseSealSha256)
            && hex64(evidence.candidateReceiptSha256)
            && hex64(evidence.hostEvidenceSha256)
            && hex64(evidence.expectedPackagedProfileSha256)
            && hex64(evidence.expectedPackagedProfileMetadataSha256)
            && evidence.hostGates.keySet().equals(new LinkedHashSet<>(hostGateIds()));
        List<FinalReleaseSealReport.Gate> gates = new ArrayList<>();
        for (Map.Entry<String, String> entry : TITLES.entrySet()) {
            String id = entry.getKey();
            if (SIGNED_APK.equals(id)) {
                boolean passed = envelopeReady && evidence.signedApkReady
                    && !clean(evidence.signedApkDetail).isEmpty()
                    && fresh(evidence.signedApkCapturedAt, evidence.sessionStartedAt, now)
                    && evidence.signedApkCapturedAt >= evidence.capturedAt;
                gates.add(gate(id, entry.getValue(), passed,
                    envelopeReady ? evidence.signedApkDetail : envelopeError(evidence),
                    evidence.signedApkCapturedAt));
            } else if (PACKAGED_BASELINE_PROFILE.equals(id)) {
                boolean passed = envelopeReady && evidence.packagedBaselineProfileReady
                    && !clean(evidence.packagedBaselineProfileDetail).isEmpty()
                    && fresh(evidence.packagedBaselineProfileCapturedAt, evidence.sessionStartedAt, now)
                    && evidence.packagedBaselineProfileCapturedAt >= evidence.capturedAt;
                gates.add(gate(id, entry.getValue(), passed,
                    envelopeReady ? evidence.packagedBaselineProfileDetail : envelopeError(evidence),
                    evidence.packagedBaselineProfileCapturedAt));
            } else {
                HostGate host = evidence.hostGates.get(id);
                boolean passed = envelopeReady && host != null && host.passed
                    && !clean(host.detail).isEmpty()
                    && fresh(host.capturedAt, evidence.sessionStartedAt, now)
                    && host.capturedAt <= evidence.capturedAt;
                String detail;
                long capturedAt;
                if (!envelopeReady) {
                    detail = envelopeError(evidence);
                    capturedAt = evidence.capturedAt;
                } else if (host == null) {
                    detail = "Required host evidence is missing";
                    capturedAt = 0L;
                } else if (!fresh(host.capturedAt, evidence.sessionStartedAt, now)
                    || host.capturedAt > evidence.capturedAt) {
                    detail = "Host evidence is stale, predates the session, postdates its envelope, or is in the future";
                    capturedAt = host.capturedAt;
                } else if (clean(host.detail).isEmpty()) {
                    detail = "Host evidence detail is empty";
                    capturedAt = host.capturedAt;
                } else {
                    detail = host.detail;
                    capturedAt = host.capturedAt;
                }
                gates.add(gate(id, entry.getValue(), passed, detail, capturedAt));
            }
        }
        return new FinalReleaseSealReport(now, evidence.versionName, evidence.versionCode,
            evidence.sourceSealSha256, gates);
    }

    private static boolean fresh(long capturedAt, long sessionStartedAt, long now) {
        return capturedAt > 0L
            && sessionStartedAt > 0L
            && capturedAt >= sessionStartedAt - MAX_FUTURE_SKEW_MS
            && capturedAt <= now + MAX_FUTURE_SKEW_MS
            && now - capturedAt <= MAX_EVIDENCE_AGE_MS;
    }

    private static boolean hex64(String value) {
        return clean(value).matches("[a-fA-F0-9]{64}");
    }

    private static String envelopeError(Evidence evidence) {
        return "Bundled host evidence invalid: " + (clean(evidence.evidenceError).isEmpty()
            ? "session, freshness, version, complete hash binding, profile, or exact gate-set mismatch"
            : evidence.evidenceError);
    }

    private static FinalReleaseSealReport.Gate gate(String id, String title, boolean pass,
                                                     String detail, long capturedAt) {
        String safeDetail = clean(detail);
        if (safeDetail.isEmpty()) safeDetail = pass ? "Verified" : "Required evidence is missing";
        return new FinalReleaseSealReport.Gate(id, title, safeDetail,
            pass ? FinalReleaseSealReport.PASS : FinalReleaseSealReport.BLOCKER, capturedAt);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
