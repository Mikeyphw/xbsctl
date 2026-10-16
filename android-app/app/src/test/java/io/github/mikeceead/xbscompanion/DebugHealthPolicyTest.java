package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public final class DebugHealthPolicyTest {
    private static final long NOW = 200_000_000L;

    @Test public void healthyEvidenceProducesStableHealthyReport() {
        DebugHealthReport report = DebugHealthPolicy.evaluate(goodEvidence());
        assertTrue(report.evaluated());
        assertEquals("HEALTHY", report.state());
        assertEquals(0, report.blockerCount());
        assertEquals(0, report.warningCount());
        assertEquals(0, report.unknownCount());
        assertTrue(report.passCount() >= 12);
        assertTrue(has(report, "root_evidence", DebugHealthReport.PASS));
        assertTrue(has(report, "schema_compatibility", DebugHealthReport.PASS));
        assertTrue(has(report, "process_visibility", DebugHealthReport.PASS));
    }

    @Test public void missingCachedEvidenceIsUnknownNotFabricatedFailure() {
        DebugHealthPolicy.Evidence evidence = goodEvidence();
        evidence.diagnosticsCapturedAt = 0L;
        evidence.compatibilityLive = false;
        evidence.compatibilityCapturedAt = 0L;
        evidence.runtimeEvidencePresent = false;
        evidence.processEvidencePresent = false;
        DebugHealthReport report = DebugHealthPolicy.evaluate(evidence);
        assertEquals(0, report.blockerCount());
        assertTrue(report.unknownCount() >= 7);
        assertTrue(has(report, "root_access", DebugHealthReport.UNKNOWN));
        assertTrue(has(report, "schema_compatibility", DebugHealthReport.UNKNOWN));
        assertTrue(has(report, "runtime_state", DebugHealthReport.UNKNOWN));
        assertTrue(has(report, "process_visibility", DebugHealthReport.UNKNOWN));
    }

    @Test public void staleRootEvidenceIsNotTreatedAsCurrentTruth() {
        DebugHealthPolicy.Evidence evidence = goodEvidence();
        evidence.diagnosticsCapturedAt = NOW - DebugHealthPolicy.ROOT_STALE_AFTER_MS - 1L;
        evidence.rootGranted = false;
        DebugHealthReport report = DebugHealthPolicy.evaluate(evidence);
        assertTrue(has(report, "root_evidence", DebugHealthReport.WARNING));
        assertTrue(has(report, "root_access", DebugHealthReport.UNKNOWN));
        assertFalse(has(report, "root_access", DebugHealthReport.BLOCKER));
    }

    @Test public void freshRootAndCompatibilityFailuresBlock() {
        DebugHealthPolicy.Evidence evidence = goodEvidence();
        evidence.rootGranted = false;
        evidence.binariesReady = false;
        evidence.compatibilityCompatible = false;
        DebugHealthReport report = DebugHealthPolicy.evaluate(evidence);
        assertTrue(has(report, "root_access", DebugHealthReport.BLOCKER));
        assertTrue(has(report, "xbs_binaries", DebugHealthReport.BLOCKER));
        assertTrue(has(report, "schema_compatibility", DebugHealthReport.BLOCKER));
    }

    @Test public void unsafeTransactionAndOrphanedRunBlock() {
        DebugHealthPolicy.Evidence evidence = goodEvidence();
        evidence.transactionStatus = "rollback_failed";
        evidence.activeCommandCount = 2;
        evidence.commandServiceActive = false;
        DebugHealthReport report = DebugHealthPolicy.evaluate(evidence);
        assertTrue(has(report, "config_transaction", DebugHealthReport.BLOCKER));
        assertTrue(has(report, "command_service", DebugHealthReport.BLOCKER));
    }

    @Test public void userControlledAndPartialEvidenceRemainWarnings() {
        DebugHealthPolicy.Evidence evidence = goodEvidence();
        evidence.notificationsReady = false;
        evidence.monitorConfigured = true;
        evidence.monitorServiceActive = false;
        evidence.processPartial = true;
        DebugHealthReport report = DebugHealthPolicy.evaluate(evidence);
        assertTrue(has(report, "notifications", DebugHealthReport.WARNING));
        assertTrue(has(report, "event_monitor", DebugHealthReport.WARNING));
        assertTrue(has(report, "process_visibility", DebugHealthReport.WARNING));
    }

    @Test public void supportTextIsBoundedAndUsesStableIdentifiers() {
        DebugHealthReport report = DebugHealthPolicy.evaluate(goodEvidence());
        String text = report.supportText();
        assertTrue(text.contains("summary=state=HEALTHY"));
        assertTrue(text.contains("check.root_evidence=pass"));
        assertTrue(text.contains("check.history_store=pass"));
        assertTrue(text.contains("check.process_visibility=pass"));
        assertTrue(report.items.size() <= DebugHealthReport.MAX_ITEMS);
    }

    @Test public void transactionClassificationIsExact() {
        assertEquals(DebugHealthReport.PASS, DebugHealthPolicy.transactionSeverity("committed"));
        assertEquals(DebugHealthReport.PASS, DebugHealthPolicy.transactionSeverity("none"));
        assertEquals(DebugHealthReport.UNKNOWN, DebugHealthPolicy.transactionSeverity("not_cached"));
        assertEquals(DebugHealthReport.BLOCKER, DebugHealthPolicy.transactionSeverity("promoting"));
        assertEquals(DebugHealthReport.BLOCKER, DebugHealthPolicy.transactionSeverity("recovery_failed"));
    }

    @Test public void missingPassiveHistoryEvidenceIsUnknownAndDoesNotFabricateOrphanedRuns() {
        DebugHealthPolicy.Evidence evidence = goodEvidence();
        evidence.historyEvidencePresent = false;
        evidence.historyCapturedAt = 0L;
        evidence.historyDatabaseReady = false;
        evidence.historyMigrationComplete = false;
        evidence.activeCommandCount = 3;
        evidence.commandServiceActive = false;
        DebugHealthReport report = DebugHealthPolicy.evaluate(evidence);
        assertTrue(has(report, "history_store", DebugHealthReport.UNKNOWN));
        assertTrue(has(report, "command_service", DebugHealthReport.UNKNOWN));
    }

    @Test public void stalePassiveHistoryAndTransactionEvidenceRemainWarnings() {
        DebugHealthPolicy.Evidence evidence = goodEvidence();
        evidence.historyCapturedAt = evidence.now - DebugHealthPolicy.HISTORY_STALE_AFTER_MS - 1L;
        evidence.historyDatabaseReady = false;
        evidence.transactionCapturedAt = evidence.now - DebugHealthPolicy.TRANSACTION_STALE_AFTER_MS - 1L;
        evidence.transactionStatus = "preparing";
        DebugHealthReport report = DebugHealthPolicy.evaluate(evidence);
        assertTrue(has(report, "history_store", DebugHealthReport.WARNING));
        assertTrue(has(report, "config_transaction", DebugHealthReport.WARNING));
    }

    @Test public void transactionWithoutPassiveTimestampIsUnknown() {
        DebugHealthPolicy.Evidence evidence = goodEvidence();
        evidence.transactionCapturedAt = 0L;
        evidence.transactionStatus = "preparing";
        DebugHealthReport report = DebugHealthPolicy.evaluate(evidence);
        assertTrue(has(report, "config_transaction", DebugHealthReport.UNKNOWN));
    }

    @Test public void missingBridgeEvidenceIsUnknown() {
        DebugHealthPolicy.Evidence evidence = goodEvidence();
        evidence.eventBridgeEvidencePresent = false;
        evidence.eventBridgeCapturedAt = 0L;
        evidence.eventBridgeAuthenticated = false;
        DebugHealthReport report = DebugHealthPolicy.evaluate(evidence);
        assertTrue(has(report, "event_bridge", DebugHealthReport.UNKNOWN));
    }

    @Test public void authenticatedBridgePassesAndFallbackWarns() {
        DebugHealthPolicy.Evidence authenticated = goodEvidence();
        assertTrue(has(DebugHealthPolicy.evaluate(authenticated), "event_bridge", DebugHealthReport.PASS));

        DebugHealthPolicy.Evidence fallback = goodEvidence();
        fallback.eventBridgeAuthenticated = false;
        fallback.eventBridgeFallback = true;
        assertTrue(has(DebugHealthPolicy.evaluate(fallback), "event_bridge", DebugHealthReport.WARNING));
    }

    @Test public void staleBridgeEvidenceWarnsWithoutFabricatingSecurityBlocker() {
        DebugHealthPolicy.Evidence evidence = goodEvidence();
        evidence.eventBridgeCapturedAt = evidence.now - DebugHealthPolicy.EVENT_BRIDGE_STALE_AFTER_MS - 1L;
        evidence.eventBridgeAuthenticated = false;
        evidence.eventBridgeSecurityFailure = true;
        evidence.monitorConfigured = true;
        DebugHealthReport report = DebugHealthPolicy.evaluate(evidence);
        assertTrue(has(report, "event_bridge", DebugHealthReport.WARNING));
        assertFalse(has(report, "event_bridge", DebugHealthReport.BLOCKER));
    }

    @Test public void currentBridgeSecurityFailureBlocksConfiguredMonitor() {
        DebugHealthPolicy.Evidence evidence = goodEvidence();
        evidence.eventBridgeAuthenticated = false;
        evidence.eventBridgeSecurityFailure = true;
        evidence.monitorConfigured = true;
        assertTrue(has(DebugHealthPolicy.evaluate(evidence), "event_bridge", DebugHealthReport.BLOCKER));
    }

    private static DebugHealthPolicy.Evidence goodEvidence() {
        DebugHealthPolicy.Evidence e = new DebugHealthPolicy.Evidence();
        e.now = NOW;
        e.diagnosticsCapturedAt = NOW - 1_000L;
        e.rootGranted = true;
        e.binariesReady = true;
        e.storageReady = true;
        e.foregroundServicesReady = true;
        e.compatibilityCapturedAt = NOW - 1_000L;
        e.compatibilityLive = true;
        e.compatibilityCompatible = true;
        e.compatibilitySchemaDigestsMatch = true;
        e.compatibilitySummary = "Live module contract compatible";
        e.historyEvidencePresent = true;
        e.historyCapturedAt = NOW - 1_000L;
        e.historyDatabaseReady = true;
        e.historyMigrationComplete = true;
        e.transactionCapturedAt = NOW - 1_000L;
        e.transactionStatus = "committed";
        e.notificationsReady = true;
        e.notificationsSummary = "all channels ready";
        e.activeCommandCount = 0;
        e.commandServiceActive = false;
        e.monitorConfigured = false;
        e.monitorServiceActive = false;
        e.eventBridgeEvidencePresent = true;
        e.eventBridgeCapturedAt = NOW - 1_000L;
        e.eventBridgeAuthenticated = true;
        e.eventBridgeFallback = false;
        e.eventBridgeSecurityFailure = false;
        e.eventBridgeSummary = "Authenticated event snapshot accepted";
        e.runtimeEvidencePresent = true;
        e.runtimeAvailable = true;
        e.runtimeCapturedAt = NOW - 1_000L;
        e.runtimeSummary = "XBS running";
        e.processEvidencePresent = true;
        e.processAvailable = true;
        e.processCapturedAt = NOW - 1_000L;
        e.processPartial = false;
        e.processSummary = "complete process sample";
        return e;
    }

    private static boolean has(DebugHealthReport report, String id, int severity) {
        for (DebugHealthReport.Item item : report.items) {
            if (id.equals(item.id) && item.severity == severity) return true;
        }
        return false;
    }
}
