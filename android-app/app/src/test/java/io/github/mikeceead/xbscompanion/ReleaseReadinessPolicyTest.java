package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ReleaseReadinessPolicyTest {
    private static final long NOW = 1_000_000L;

    @Test public void completeReleaseEvidenceSealsReady() {
        ReleaseReadinessReport report = ReleaseReadinessPolicy.evaluate(goodEvidence());
        assertTrue(report.releaseReady());
        assertEquals("READY", report.state());
        assertEquals(0, report.blockerCount());
        assertEquals(0, report.warningCount());
        assertTrue(report.passCount() >= 14);
    }

    @Test public void debugBuildIsAttentionNotBlocker() {
        ReleaseReadinessPolicy.Evidence evidence = goodEvidence();
        evidence.debugBuild = true;
        ReleaseReadinessReport report = ReleaseReadinessPolicy.evaluate(evidence);
        assertTrue(report.releaseReady());
        assertEquals("ATTENTION", report.state());
        assertEquals(1, report.warningCount());
    }

    @Test public void staleDiagnosticsBlockRelease() {
        ReleaseReadinessPolicy.Evidence evidence = goodEvidence();
        evidence.diagnosticsFresh = false;
        ReleaseReadinessReport report = ReleaseReadinessPolicy.evaluate(evidence);
        assertFalse(report.releaseReady());
        assertTrue(has(report, "diagnostics_evidence", ReleaseReadinessReport.BLOCKER));
    }

    @Test public void unsafeTransactionStateBlocksRelease() {
        ReleaseReadinessPolicy.Evidence evidence = goodEvidence();
        evidence.transactionStatus = "rollback_failed";
        ReleaseReadinessReport report = ReleaseReadinessPolicy.evaluate(evidence);
        assertTrue(has(report, "config_transaction", ReleaseReadinessReport.BLOCKER));
    }

    @Test public void orphanedActiveRunBlocksRelease() {
        ReleaseReadinessPolicy.Evidence evidence = goodEvidence();
        evidence.activeCommandCount = 2;
        evidence.commandServiceActive = false;
        ReleaseReadinessReport report = ReleaseReadinessPolicy.evaluate(evidence);
        assertTrue(has(report, "command_service", ReleaseReadinessReport.BLOCKER));
    }

    @Test public void staleRuntimeAndProcessSamplesAreWarnings() {
        ReleaseReadinessPolicy.Evidence evidence = goodEvidence();
        evidence.runtimeCapturedAt = NOW - ReleaseReadinessPolicy.RUNTIME_STALE_AFTER_MS - 1L;
        evidence.processCapturedAt = NOW - ReleaseReadinessPolicy.PROCESS_STALE_AFTER_MS - 1L;
        ReleaseReadinessReport report = ReleaseReadinessPolicy.evaluate(evidence);
        assertTrue(report.releaseReady());
        assertTrue(has(report, "runtime_freshness", ReleaseReadinessReport.WARNING));
        assertTrue(has(report, "process_sample", ReleaseReadinessReport.WARNING));
    }

    @Test public void transactionStatusParsingIsBoundedAndExact() {
        assertEquals("committed", ReleaseReadinessPolicy.parseTransactionStatus("id=1\nstatus=committed\nmessage=ok\n"));
        assertEquals("rollback_failed", ReleaseReadinessPolicy.parseTransactionStatus("status=ROLLBACK_FAILED\n"));
        assertEquals("", ReleaseReadinessPolicy.parseTransactionStatus("message=no status\n"));
        assertEquals("", ReleaseReadinessPolicy.parseTransactionStatus(null));
        assertEquals("invalid_oversized", ReleaseReadinessPolicy.parseTransactionStatus(repeat("x", ReleaseReadinessPolicy.MAX_TRANSACTION_SUPPORT_CHARS + 1)));
        assertEquals("invalid_oversized", ReleaseReadinessPolicy.parseTransactionStatus(repeat("line\n", ReleaseReadinessPolicy.MAX_TRANSACTION_SUPPORT_LINES + 1)));
        assertEquals("invalid_oversized", ReleaseReadinessPolicy.parseTransactionStatus("status=" + repeat("x", ReleaseReadinessPolicy.MAX_TRANSACTION_LINE_CHARS + 1)));
    }


    @Test public void unavailableHistoryDatabaseBlocksRelease() {
        ReleaseReadinessPolicy.Evidence evidence = goodEvidence();
        evidence.historyDatabaseReady = false;
        ReleaseReadinessReport report = ReleaseReadinessPolicy.evaluate(evidence);
        assertTrue(has(report, "history_database", ReleaseReadinessReport.BLOCKER));
    }

    @Test public void incompleteLegacyMigrationBlocksRelease() {
        ReleaseReadinessPolicy.Evidence evidence = goodEvidence();
        evidence.historyMigrationComplete = false;
        ReleaseReadinessReport report = ReleaseReadinessPolicy.evaluate(evidence);
        assertTrue(has(report, "history_migration", ReleaseReadinessReport.BLOCKER));
    }

    @Test public void supportTextContainsStableGateEvidence() {
        ReleaseReadinessReport report = ReleaseReadinessPolicy.evaluate(goodEvidence());
        String text = report.supportText();
        assertTrue(text.contains("summary=state=READY"));
        assertTrue(text.contains("check.release_metadata=pass"));
        assertTrue(text.contains("check.config_transaction=pass"));
        assertTrue(text.contains("check.process_visibility=pass"));
        assertTrue(text.contains("check.history_database=pass"));
        assertTrue(text.contains("check.history_migration=pass"));
    }

    @Test public void missingOrFallbackBridgeEvidenceNeedsAttention() {
        ReleaseReadinessPolicy.Evidence missing = goodEvidence();
        missing.eventBridgeEvidencePresent = false;
        missing.eventBridgeAuthenticated = false;
        ReleaseReadinessReport missingReport = ReleaseReadinessPolicy.evaluate(missing);
        assertTrue(missingReport.releaseReady());
        assertTrue(has(missingReport, "event_bridge", ReleaseReadinessReport.WARNING));

        ReleaseReadinessPolicy.Evidence fallback = goodEvidence();
        fallback.eventBridgeAuthenticated = false;
        fallback.eventBridgeFallback = true;
        ReleaseReadinessReport fallbackReport = ReleaseReadinessPolicy.evaluate(fallback);
        assertTrue(fallbackReport.releaseReady());
        assertTrue(has(fallbackReport, "event_bridge", ReleaseReadinessReport.WARNING));
    }

    @Test public void bridgeSecurityFailureBlocksWhenMonitorConfigured() {
        ReleaseReadinessPolicy.Evidence evidence = goodEvidence();
        evidence.eventBridgeAuthenticated = false;
        evidence.eventBridgeSecurityFailure = true;
        evidence.monitorConfigured = true;
        ReleaseReadinessReport report = ReleaseReadinessPolicy.evaluate(evidence);
        assertFalse(report.releaseReady());
        assertTrue(has(report, "event_bridge", ReleaseReadinessReport.BLOCKER));
    }

    private static ReleaseReadinessPolicy.Evidence goodEvidence() {
        ReleaseReadinessPolicy.Evidence e = new ReleaseReadinessPolicy.Evidence();
        e.now = NOW;
        e.versionName = "4.2.0";
        e.versionCode = 15L;
        e.debugBuild = false;
        e.targetSdk = 36;
        e.supportBundleFormat = 18;
        e.diagnosticsCapturedAt = NOW - 1_000L;
        e.diagnosticsFresh = true;
        e.rootGranted = true;
        e.binariesReady = true;
        e.storageReady = true;
        e.foregroundServicesReady = true;
        e.notificationsReady = true;
        e.activeCommandCount = 0;
        e.commandServiceActive = false;
        e.monitorConfigured = true;
        e.monitorServiceActive = true;
        e.eventBridgeEvidencePresent = true;
        e.eventBridgeAuthenticated = true;
        e.eventBridgeFallback = false;
        e.eventBridgeSecurityFailure = false;
        e.eventBridgeSummary = "Authenticated event snapshot accepted";
        e.runtimeAvailable = true;
        e.runtimeCapturedAt = NOW - 1_000L;
        e.processAvailable = true;
        e.processCapturedAt = NOW - 1_000L;
        e.processPartial = false;
        e.transactionStatus = "committed";
        e.historyDatabaseReady = true;
        e.historyMigrationComplete = true;
        e.compatibilityLive = true;
        e.compatibilityFresh = true;
        e.compatibilityCompatible = true;
        e.compatibilitySchemaDigestsMatch = true;
        e.compatibilitySummary = "Live module contract compatible";
        e.securitySealReady = true;
        e.securitySealSummary = "Manifest and paths verified";
        e.adaptiveUiReady = true;
        e.adaptiveUiSummary = "Adaptive UI verified";
        return e;
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(value.length() * Math.max(0, count));
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }

    private static boolean has(ReleaseReadinessReport report, String id, int severity) {
        for (ReleaseReadinessReport.Item item : report.items) {
            if (id.equals(item.id) && item.severity == severity) return true;
        }
        return false;
    }
}
