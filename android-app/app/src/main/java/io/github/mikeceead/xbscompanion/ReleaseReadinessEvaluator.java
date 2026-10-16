package io.github.mikeceead.xbscompanion;

import android.content.Context;

/** Collects a no-prompt readiness snapshot from existing bounded frontend caches. */
final class ReleaseReadinessEvaluator {
    private ReleaseReadinessEvaluator() {}

    static ReleaseReadinessReport evaluate(Context context) {
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        RootDiagnosticsReport diagnostics = RootDiagnosticsStore.cached(app);
        NotificationController.Readiness notifications = NotificationController.readiness(app);
        XbsSnapshot runtime = XbsStateStore.current(app);
        ProcessSnapshot processes = ProcessRepository.cached(app);
        XbsHistoryStatus history = XbsHistoryStore.status(app);
        XbsCompatibilityReport compatibility = XbsCompatibilityStore.cached(app);
        EventBridgeReport eventBridge = EventBridgeStore.cached(app);

        ReleaseReadinessPolicy.Evidence evidence = new ReleaseReadinessPolicy.Evidence();
        evidence.now = now;
        evidence.versionName = BuildConfig.VERSION_NAME;
        evidence.versionCode = BuildConfig.VERSION_CODE;
        evidence.debugBuild = BuildConfig.DEBUG;
        evidence.targetSdk = app.getApplicationInfo().targetSdkVersion;
        evidence.supportBundleFormat = SupportBundleBuilder.FORMAT_VERSION;
        evidence.diagnosticsCapturedAt = diagnostics.capturedAt;
        evidence.diagnosticsFresh = diagnostics.isFresh(now);
        evidence.rootGranted = diagnostics.rootGranted;
        evidence.binariesReady = diagnostics.binariesReady();
        evidence.storageReady = diagnostics.storageReady();
        evidence.foregroundServicesReady = diagnostics.foregroundServicePermission
            && diagnostics.specialUsePermission;
        evidence.notificationsReady = notifications.permissionGranted && notifications.appEnabled
            && notifications.commandProgressChannelReady && notifications.commandResultsChannelReady
            && notifications.runtimeChannelReady && notifications.alertsChannelReady;
        evidence.activeCommandCount = Math.max(diagnostics.activeCommandCount, RunStore.activeCount(app));
        evidence.commandServiceActive = diagnostics.commandServiceActive;
        evidence.monitorConfigured = AppSettings.monitorEnabled(app);
        evidence.monitorServiceActive = diagnostics.monitorServiceActive;
        evidence.eventBridgeEvidencePresent = eventBridge.evidencePresent();
        evidence.eventBridgeAuthenticated = eventBridge.authenticated();
        evidence.eventBridgeFallback = eventBridge.fallback();
        evidence.eventBridgeSecurityFailure = eventBridge.securityFailure;
        evidence.eventBridgeSummary = eventBridge.headline() + " · " + eventBridge.compactSummary();
        evidence.runtimeAvailable = runtime.available;
        evidence.runtimeCapturedAt = runtime.capturedAt;
        evidence.processAvailable = processes.rootAvailable;
        evidence.processCapturedAt = processes.capturedAt;
        evidence.processPartial = processes.diagnostics.partial();
        evidence.transactionStatus = ReleaseReadinessPolicy.parseTransactionStatus(
            XbsConfigTransactionStore.supportText(app));
        SecuritySealReport security = SecuritySealEvaluator.evaluate(app);
        evidence.securitySealReady = security.evaluated() && security.blockerCount() == 0;
        evidence.securitySealSummary = security.headline();
        PerformanceReport performance = PerformanceStore.cached(app);
        UiQualityReport uiQuality = UiQualityStore.cached(app);
        evidence.adaptiveUiReady = uiQuality.ready(now);
        evidence.adaptiveUiSummary = uiQuality.summary() + " · " + performance.summary();
        evidence.historyDatabaseReady = history.databaseReady;
        evidence.historyMigrationComplete = history.migrationComplete;
        evidence.compatibilityLive = compatibility.isLive();
        evidence.compatibilityFresh = compatibility.isFresh(now);
        evidence.compatibilityCompatible = compatibility.compatible();
        evidence.compatibilitySchemaDigestsMatch = compatibility.schemaDigestsMatchApp();
        evidence.compatibilitySummary = compatibility.headline() + " · " + compatibility.compactSummary();
        return ReleaseReadinessPolicy.evaluate(evidence);
    }
}
