package io.github.mikeceead.xbscompanion;

import android.content.Context;

/** Aggregates bounded cached and local Android evidence without requesting root. */
final class DebugHealthEvaluator {
    private DebugHealthEvaluator() {}

    static DebugHealthReport evaluate(Context context) {
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        RootDiagnosticsReport diagnostics = RootDiagnosticsStore.cached(app);
        XbsCompatibilityReport compatibility = XbsCompatibilityStore.cached(app);
        XbsHistoryStatus history = XbsHistoryStore.peekStatus(app);
        NotificationController.Readiness notifications = NotificationController.readiness(app);
        XbsSnapshot runtime = XbsStateStore.current(app);
        ProcessSnapshot processes = ProcessRepository.cached(app);
        EventBridgeReport eventBridge = EventBridgeStore.cached(app);

        DebugHealthPolicy.Evidence evidence = new DebugHealthPolicy.Evidence();
        evidence.now = now;
        evidence.diagnosticsCapturedAt = diagnostics.capturedAt;
        evidence.rootGranted = diagnostics.rootGranted;
        evidence.binariesReady = diagnostics.binariesReady();
        evidence.storageReady = diagnostics.storageReady();
        evidence.storageFailure = DiagnosticProblemGraph.storageFailure(diagnostics);
        evidence.foregroundServicesReady = diagnostics.foregroundServicePermission
            && diagnostics.specialUsePermission;
        evidence.diagnosticsError = diagnostics.error;

        evidence.compatibilityCapturedAt = compatibility.capturedAt;
        evidence.compatibilityLive = compatibility.isLive();
        evidence.compatibilityCompatible = compatibility.compatible();
        evidence.compatibilitySchemaDigestsMatch = compatibility.schemaDigestsMatchApp();
        evidence.compatibilitySummary = compatibility.headline() + " · " + compatibility.compactSummary();

        evidence.historyEvidencePresent = history.cached();
        evidence.historyCapturedAt = history.capturedAt;
        evidence.historyDatabaseReady = history.databaseReady;
        evidence.historyMigrationComplete = history.migrationComplete;
        evidence.historyError = history.error;
        evidence.transactionCapturedAt = XbsConfigTransactionStore.peekCapturedAt(app);
        evidence.transactionStatus = ReleaseReadinessPolicy.parseTransactionStatus(
            XbsConfigTransactionStore.peekSupportText(app));

        evidence.notificationsReady = notifications.permissionGranted && notifications.appEnabled
            && notifications.commandProgressChannelReady && notifications.commandResultsChannelReady
            && notifications.runtimeChannelReady && notifications.alertsChannelReady;
        evidence.notificationsSummary = notifications.summary();

        evidence.activeCommandCount = RunStore.peekActiveCount(app);
        evidence.commandServiceActive = RootCommandService.isServiceActive();
        evidence.monitorConfigured = AppSettings.monitorEnabled(app);
        evidence.monitorServiceActive = EventMonitorService.isServiceActive();
        evidence.eventBridgeEvidencePresent = eventBridge.evidencePresent();
        evidence.eventBridgeCapturedAt = eventBridge.capturedAt;
        evidence.eventBridgeAuthenticated = eventBridge.authenticated();
        evidence.eventBridgeFallback = eventBridge.fallback();
        evidence.eventBridgeSecurityFailure = eventBridge.securityFailure;
        evidence.eventBridgeSummary = eventBridge.headline() + " · " + eventBridge.compactSummary();

        evidence.runtimeEvidencePresent = runtimeEvidencePresent(runtime);
        evidence.runtimeAvailable = runtime.available;
        evidence.runtimeCapturedAt = runtime.capturedAt;
        evidence.runtimeSummary = runtime.headline() + " · " + runtime.summary();

        evidence.processEvidencePresent = processEvidencePresent(processes);
        evidence.processAvailable = processes.rootAvailable;
        evidence.processCapturedAt = processes.capturedAt;
        evidence.processPartial = processes.diagnostics.partial();
        evidence.processFailure = DiagnosticProblemGraph.processFailure(processes);
        evidence.processSummary = processes.rootAvailable
            ? processes.diagnostics.summary()
            : clean(processes.error);

        DebugHealthReport report = DebugHealthPolicy.evaluate(evidence);
        DebugHealthStore.save(app, report);
        return report;
    }

    private static boolean runtimeEvidencePresent(XbsSnapshot snapshot) {
        if (snapshot == null) return false;
        String error = clean(snapshot.error).toLowerCase(java.util.Locale.US);
        return !error.contains("not been refreshed yet");
    }

    private static boolean processEvidencePresent(ProcessSnapshot snapshot) {
        if (snapshot == null) return false;
        String error = clean(snapshot.error).toLowerCase(java.util.Locale.US);
        return !error.contains("no process sample cached yet");
    }

    private static String notificationSummary(NotificationController.Readiness readiness) {
        return "permission=" + readiness.permissionGranted
            + " app=" + readiness.appEnabled
            + " progress=" + readiness.commandProgressChannelReady
            + " results=" + readiness.commandResultsChannelReady
            + " runtime=" + readiness.runtimeChannelReady
            + " alerts=" + readiness.alertsChannelReady;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
