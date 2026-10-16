package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure-Java release gate. Android collection is deliberately isolated in ReleaseReadinessEvaluator. */
final class ReleaseReadinessPolicy {
    static final long RUNTIME_STALE_AFTER_MS = 2 * 60_000L;
    static final long PROCESS_STALE_AFTER_MS = 5 * 60_000L;
    static final int REQUIRED_SUPPORT_BUNDLE_FORMAT = 18;
    static final int REQUIRED_TARGET_SDK = 36;
    static final long REQUIRED_VERSION_CODE = 15L;
    static final int MAX_TRANSACTION_SUPPORT_CHARS = 4096;
    static final int MAX_TRANSACTION_SUPPORT_LINES = 32;
    static final int MAX_TRANSACTION_LINE_CHARS = 512;

    private ReleaseReadinessPolicy() {}

    static final class Evidence {
        long now;
        String versionName = "";
        long versionCode;
        boolean debugBuild;
        int targetSdk;
        int supportBundleFormat;
        long diagnosticsCapturedAt;
        boolean diagnosticsFresh;
        boolean rootGranted;
        boolean binariesReady;
        boolean storageReady;
        DiagnosticProblemGraph.FailureType storageFailure = DiagnosticProblemGraph.FailureType.NONE;
        boolean foregroundServicesReady;
        boolean notificationsReady;
        int activeCommandCount;
        boolean commandServiceActive;
        boolean monitorConfigured;
        boolean monitorServiceActive;
        boolean eventBridgeEvidencePresent;
        boolean eventBridgeAuthenticated;
        boolean eventBridgeFallback;
        boolean eventBridgeSecurityFailure;
        String eventBridgeSummary = "";
        boolean runtimeAvailable;
        long runtimeCapturedAt;
        boolean processAvailable;
        long processCapturedAt;
        boolean processPartial;
        DiagnosticProblemGraph.FailureType processFailure = DiagnosticProblemGraph.FailureType.PROCESS_NOT_SAMPLED;
        String processError = "";
        String transactionStatus = "";
        boolean historyDatabaseReady = true;
        boolean historyMigrationComplete = true;
        boolean compatibilityLive;
        boolean compatibilityFresh;
        boolean compatibilityCompatible;
        boolean compatibilitySchemaDigestsMatch;
        String compatibilitySummary = "";
        boolean securitySealReady;
        String securitySealSummary = "";
        boolean adaptiveUiReady;
        String adaptiveUiSummary = "";
    }

    static ReleaseReadinessReport evaluate(Evidence evidence) {
        Evidence e = evidence == null ? new Evidence() : evidence;
        long now = Math.max(0L, e.now);
        List<ReleaseReadinessReport.Item> items = new ArrayList<>();

        boolean versionReady = e.versionCode >= REQUIRED_VERSION_CODE
            && e.versionName != null && !e.versionName.trim().isEmpty()
            && !e.versionName.toLowerCase(Locale.US).contains("phase");
        add(items, "security_seal", "Manifest, path, and stale-action security seal",
            e.securitySealReady ? "Security seal passes" : detailOr(e.securitySealSummary, "Security seal has blockers"),
            e.securitySealReady ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.BLOCKER);
        add(items, "adaptive_ui", "Accessibility and adaptive UI seal",
            e.adaptiveUiReady ? "Touch targets, edge-to-edge, predictive back, and adaptive navigation are enabled" : detailOr(e.adaptiveUiSummary, "Adaptive UI evidence is incomplete"),
            e.adaptiveUiReady ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.WARNING);

        add(items, "release_metadata", "Release metadata",
            versionReady ? "Version " + e.versionName + " (" + e.versionCode + ")"
                : "Use a non-phase version name and versionCode " + REQUIRED_VERSION_CODE + " or newer",
            versionReady ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.BLOCKER);

        add(items, "build_variant", "Build variant",
            e.debugBuild ? "Debug build detected; create a signed release artifact for distribution"
                : "Non-debug build detected",
            e.debugBuild ? ReleaseReadinessReport.WARNING : ReleaseReadinessReport.PASS);

        add(items, "target_sdk", "Target SDK",
            "targetSdk=" + e.targetSdk + " required=" + REQUIRED_TARGET_SDK,
            e.targetSdk >= REQUIRED_TARGET_SDK ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.BLOCKER);

        add(items, "support_bundle", "Support bundle schema",
            "format=" + e.supportBundleFormat + " required=" + REQUIRED_SUPPORT_BUNDLE_FORMAT,
            e.supportBundleFormat >= REQUIRED_SUPPORT_BUNDLE_FORMAT
                ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.BLOCKER);

        boolean diagnosticsPresent = e.diagnosticsCapturedAt > 0L;
        add(items, "diagnostics_evidence", "Root diagnostics evidence",
            !diagnosticsPresent ? "Run full diagnostics before release"
                : e.diagnosticsFresh ? "Fresh cached diagnostics"
                : "Diagnostics are stale; run them again before release",
            diagnosticsPresent && e.diagnosticsFresh
                ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.BLOCKER);

        add(items, "root_access", "Root access",
            e.rootGranted ? "Root grant is available" : "Root access is not granted",
            e.rootGranted ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.BLOCKER);
        add(items, "xbs_binaries", "XBS binaries",
            e.binariesReady ? "xbs and XBSctl are executable" : "xbs or XBSctl is missing or not executable",
            e.binariesReady ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.BLOCKER);
        add(items, "schema_compatibility", "Schema compatibility",
            e.compatibilitySummary == null || e.compatibilitySummary.trim().isEmpty()
                ? "Compatibility evidence is unavailable" : e.compatibilitySummary,
            !e.compatibilityLive ? ReleaseReadinessReport.WARNING
                : !e.compatibilityCompatible ? ReleaseReadinessReport.BLOCKER
                : e.compatibilityFresh && e.compatibilitySchemaDigestsMatch
                    ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.WARNING);
        add(items, "storage_paths", "Managed storage paths",
            e.storageReady ? "Config, lists, and backups are readable and writable"
                : DiagnosticProblemGraph.storageDetail(e.storageFailure),
            e.storageReady ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.BLOCKER);
        add(items, "foreground_services", "Foreground-service declarations",
            e.foregroundServicesReady ? "Foreground-service permissions and declarations are ready"
                : "Foreground-service setup is incomplete",
            e.foregroundServicesReady ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.BLOCKER);

        boolean commandConsistent = e.activeCommandCount == 0 || e.commandServiceActive;
        add(items, "command_service", "Active-command consistency",
            commandConsistent ? e.activeCommandCount + " active command record(s) are service-consistent"
                : e.activeCommandCount + " active command record(s) exist without the command service",
            commandConsistent ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.BLOCKER);

        int transactionSeverity = transactionSeverity(e.transactionStatus);
        add(items, "config_transaction", "Configuration transaction journal",
            transactionDetail(e.transactionStatus), transactionSeverity);

        add(items, "history_database", "Local history database",
            e.historyDatabaseReady ? "Room history database is available"
                : "Room history database is unavailable; command evidence may not persist",
            e.historyDatabaseReady ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.BLOCKER);
        add(items, "history_migration", "Legacy history migration",
            e.historyMigrationComplete ? "Legacy SharedPreferences history migration is complete"
                : "Legacy run history migration has not completed",
            e.historyMigrationComplete ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.BLOCKER);


        int bridgeSeverity;
        if (!e.eventBridgeEvidencePresent) bridgeSeverity = ReleaseReadinessReport.WARNING;
        else if (e.eventBridgeSecurityFailure) bridgeSeverity = e.monitorConfigured
            ? ReleaseReadinessReport.BLOCKER : ReleaseReadinessReport.WARNING;
        else if (e.eventBridgeAuthenticated) bridgeSeverity = ReleaseReadinessReport.PASS;
        else if (e.eventBridgeFallback) bridgeSeverity = ReleaseReadinessReport.WARNING;
        else bridgeSeverity = ReleaseReadinessReport.WARNING;
        add(items, "event_bridge", "Authenticated event bridge",
            e.eventBridgeSummary == null || e.eventBridgeSummary.trim().isEmpty()
                ? "No authenticated event-bridge evidence is cached" : e.eventBridgeSummary,
            bridgeSeverity);

        boolean configBlocksRuntime = !e.runtimeAvailable
            && e.storageFailure == DiagnosticProblemGraph.FailureType.CONFIG_MISSING;
        add(items, "runtime_state", "XBS runtime state",
            e.runtimeAvailable ? "Runtime state is available"
                : configBlocksRuntime ? "Blocked by missing canonical configuration; counted under Managed storage paths"
                    : "Runtime state is unavailable",
            e.runtimeAvailable ? ReleaseReadinessReport.PASS
                : configBlocksRuntime ? ReleaseReadinessReport.WARNING : ReleaseReadinessReport.BLOCKER);
        if (e.runtimeAvailable) {
            boolean fresh = fresh(now, e.runtimeCapturedAt, RUNTIME_STALE_AFTER_MS);
            add(items, "runtime_freshness", "Runtime evidence freshness",
                fresh ? "Runtime sample is current" : "Runtime sample is older than two minutes",
                fresh ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.WARNING);
        }

        add(items, "notifications", "Notification readiness",
            e.notificationsReady ? "Permission, app setting, and channels are ready"
                : "Notification permission, app setting, or a required channel needs attention",
            e.notificationsReady ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.WARNING);

        boolean monitorReady = !e.monitorConfigured || e.monitorServiceActive;
        add(items, "event_monitor", "Persistent event monitor",
            !e.monitorConfigured ? "Monitor is intentionally disabled"
                : e.monitorServiceActive ? "Configured monitor is running"
                : "Monitor is configured but not running",
            monitorReady ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.WARNING);

        if (!e.processAvailable) {
            boolean internalFailure = e.processFailure == DiagnosticProblemGraph.FailureType.PROCESS_INTERNAL_ERROR;
            add(items, "process_sample", internalFailure ? "Process monitor functional check" : "Process monitor evidence",
                DiagnosticProblemGraph.processDetail(e.processFailure, e.processError),
                internalFailure ? ReleaseReadinessReport.BLOCKER : ReleaseReadinessReport.WARNING);
        } else {
            boolean processFresh = fresh(now, e.processCapturedAt, PROCESS_STALE_AFTER_MS);
            add(items, "process_sample", "Process monitor evidence",
                processFresh ? "Process sample is current" : "Process sample is older than five minutes",
                processFresh ? ReleaseReadinessReport.PASS : ReleaseReadinessReport.WARNING);
            add(items, "process_visibility", "Process visibility",
                e.processPartial ? "The cached /proc sample is partial or restricted"
                    : "The cached /proc sample is complete",
                e.processPartial ? ReleaseReadinessReport.WARNING : ReleaseReadinessReport.PASS);
        }

        return new ReleaseReadinessReport(now, e.versionName, e.versionCode, e.debugBuild, items);
    }

    private static void add(List<ReleaseReadinessReport.Item> items, String id, String title,
                            String detail, int severity) {
        items.add(new ReleaseReadinessReport.Item(id, title, detail, severity));
    }

    private static String detailOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static boolean fresh(long now, long capturedAt, long maximumAge) {
        return capturedAt > 0L && now >= capturedAt && now - capturedAt <= maximumAge;
    }

    private static int transactionSeverity(String status) {
        String normalized = normalize(status);
        if (normalized.isEmpty() || "not_cached".equals(normalized)) return ReleaseReadinessReport.WARNING;
        if ("none".equals(normalized) || "committed".equals(normalized)
            || "rolled_back".equals(normalized) || "recovered".equals(normalized)) {
            return ReleaseReadinessReport.PASS;
        }
        return ReleaseReadinessReport.BLOCKER;
    }

    private static String transactionDetail(String status) {
        String normalized = normalize(status);
        if (normalized.isEmpty() || "not_cached".equals(normalized)) {
            return "Open Configuration to cache the latest transaction journal state";
        }
        if ("none".equals(normalized)) return "No configuration transaction has been recorded";
        if ("committed".equals(normalized)) return "Latest transaction committed and verified";
        if ("rolled_back".equals(normalized)) return "Latest transaction was rolled back cleanly";
        if ("recovered".equals(normalized)) return "Latest interrupted transaction was recovered";
        return "Transaction status requires recovery or inspection: " + normalized;
    }

    static String parseTransactionStatus(String supportText) {
        if (supportText == null) return "";
        if (supportText.length() > MAX_TRANSACTION_SUPPORT_CHARS) return "invalid_oversized";
        String[] lines = supportText.split("\\r?\\n", -1);
        if (lines.length > MAX_TRANSACTION_SUPPORT_LINES) return "invalid_oversized";
        for (String line : lines) {
            if (line.length() > MAX_TRANSACTION_LINE_CHARS) return "invalid_oversized";
            String clean = line.trim();
            if (clean.startsWith("status=")) return normalize(clean.substring("status=".length()));
        }
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
