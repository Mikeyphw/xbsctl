package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FinalReleaseSealPolicyTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test public void missingEvidenceBlocksAllGates() {
        FinalReleaseSealPolicy.Evidence evidence = new FinalReleaseSealPolicy.Evidence();
        evidence.evaluatedAt = NOW;
        evidence.versionName = "4.2.0";
        evidence.versionCode = 15;
        FinalReleaseSealReport report = FinalReleaseSealPolicy.evaluate(evidence);
        assertEquals(14, report.gates.size());
        assertEquals(0, report.passCount());
        assertFalse(report.ready());
    }

    @Test public void completeExactFreshEvidencePassesAllFourteenGates() {
        FinalReleaseSealReport report = FinalReleaseSealPolicy.evaluate(readyEvidence());
        assertEquals(14, report.gates.size());
        assertEquals(14, report.passCount());
        assertTrue(report.ready());
    }

    @Test public void staleEvidenceBlocksEveryGate() {
        FinalReleaseSealPolicy.Evidence evidence = readyEvidence();
        evidence.sessionStartedAt = 1L;
        evidence.capturedAt = 2L;
        for (String id : FinalReleaseSealPolicy.hostGateIds()) {
            evidence.hostGates.put(id, new FinalReleaseSealPolicy.HostGate(true, "stale", 2L));
        }
        evidence.signedApkCapturedAt = 2L;
        evidence.packagedBaselineProfileCapturedAt = 2L;
        FinalReleaseSealReport report = FinalReleaseSealPolicy.evaluate(evidence);
        assertEquals(0, report.passCount());
        assertFalse(report.ready());
    }

    @Test public void futureEvidenceBlocksEveryGate() {
        FinalReleaseSealPolicy.Evidence evidence = readyEvidence();
        evidence.capturedAt = NOW + FinalReleaseSealPolicy.MAX_FUTURE_SKEW_MS + 1L;
        assertFalse(FinalReleaseSealPolicy.evaluate(evidence).ready());
    }

    @Test public void extraOrMissingHostGateInvalidatesEnvelope() {
        FinalReleaseSealPolicy.Evidence evidence = readyEvidence();
        evidence.hostGates.remove(FinalReleaseSealPolicy.ARTIFACT_REPLAY);
        assertEquals(0, FinalReleaseSealPolicy.evaluate(evidence).passCount());
        evidence = readyEvidence();
        evidence.hostGates.put("forged_extra", new FinalReleaseSealPolicy.HostGate(true, "forged", NOW));
        assertEquals(0, FinalReleaseSealPolicy.evaluate(evidence).passCount());
    }

    @Test public void versionMismatchInvalidatesEveryGate() {
        FinalReleaseSealPolicy.Evidence evidence = readyEvidence();
        evidence.evidenceVersionCode = 14;
        FinalReleaseSealReport report = FinalReleaseSealPolicy.evaluate(evidence);
        assertEquals(0, report.passCount());
        assertFalse(report.ready());
    }

    @Test public void oneFailedHostGateCannotBeHiddenByRuntimeChecks() {
        FinalReleaseSealPolicy.Evidence evidence = readyEvidence();
        evidence.hostGates.put(FinalReleaseSealPolicy.ARTIFACT_REPLAY,
            new FinalReleaseSealPolicy.HostGate(false, "replay mismatch", NOW - 1_000L));
        FinalReleaseSealReport report = FinalReleaseSealPolicy.evaluate(evidence);
        assertEquals(13, report.passCount());
        assertFalse(report.ready());
    }

    private static FinalReleaseSealPolicy.Evidence readyEvidence() {
        FinalReleaseSealPolicy.Evidence evidence = new FinalReleaseSealPolicy.Evidence();
        evidence.evaluatedAt = NOW;
        evidence.sessionStartedAt = NOW - 2_000L;
        evidence.capturedAt = NOW - 1_000L;
        evidence.sessionId = "a".repeat(64);
        evidence.versionName = "4.2.0";
        evidence.versionCode = 15;
        evidence.evidenceVersionName = "4.2.0";
        evidence.evidenceVersionCode = 15;
        evidence.sourceSealSha256 = "b".repeat(64);
        evidence.promiseSealSha256 = "c".repeat(64);
        evidence.candidateReceiptSha256 = "d".repeat(64);
        evidence.hostEvidenceSha256 = "e".repeat(64);
        evidence.expectedPackagedProfileSha256 = "f".repeat(64);
        evidence.expectedPackagedProfileMetadataSha256 = "1".repeat(64);
        for (String id : FinalReleaseSealPolicy.hostGateIds()) {
            evidence.hostGates.put(id, new FinalReleaseSealPolicy.HostGate(true, "verified", NOW - 1_000L));
        }
        evidence.signedApkReady = true;
        evidence.signedApkDetail = "certificate matched";
        evidence.signedApkCapturedAt = NOW;
        evidence.packagedBaselineProfileReady = true;
        evidence.packagedBaselineProfileDetail = "profile matched";
        evidence.packagedBaselineProfileCapturedAt = NOW;
        return evidence;
    }
}
