package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure-Java policy for the read-only Debug Workbench health model. */
final class DebugHealthPolicy {
    static final long ROOT_STALE_AFTER_MS = 5 * 60_000L;
    static final long COMPATIBILITY_STALE_AFTER_MS = 6 * 60 * 60_000L;
    static final long RUNTIME_STALE_AFTER_MS = 2 * 60_000L;
    static final long PROCESS_STALE_AFTER_MS = 5 * 60_000L;
    static final long HISTORY_STALE_AFTER_MS = 6 * 60 * 60_000L;
    static final long TRANSACTION_STALE_AFTER_MS = 24 * 60 * 60_000L;
    static final long EVENT_BRIDGE_STALE_AFTER_MS = 6 * 60 * 60_000L;
    static final int MAX_EVIDENCE_TEXT_CHARS = 512;

    static final String DOMAIN_ROOT = "Root and module";
    static final String DOMAIN_STORAGE = "Storage and history";
    static final String DOMAIN_RUNTIME = "Runtime and services";
    static final String DOMAIN_NOTIFICATIONS = "Notifications";
    static final String DOMAIN_OBSERVABILITY = "Observability";

    static final String ACTION_ROOT = "root_diagnostics";
    static final String ACTION_CONFIGURATION = "configuration";
    static final String ACTION_ACTIVITY = "activity";
    static final String ACTION_RUNTIME = "runtime";
    static final String ACTION_PROCESS = "process";
    static final String ACTION_NOTIFICATIONS = "notifications";

    private DebugHealthPolicy() {}

    static final class Evidence {
        long now;

        long diagnosticsCapturedAt;
        boolean rootGranted;
        boolean binariesReady;
        boolean storageReady;
        boolean foregroundServicesReady;
        String diagnosticsError = "";

        long compatibilityCapturedAt;
        boolean compatibilityLive;
        boolean compatibilityCompatible;
        boolean compatibilitySchemaDigestsMatch;
        String compatibilitySummary = "";

        boolean historyEvidencePresent;
        long historyCapturedAt;
        boolean historyDatabaseReady;
        boolean historyMigrationComplete;
        String historyError = "";
        long transactionCapturedAt;
        String transactionStatus = "";

        boolean notificationsReady;
        String notificationsSummary = "";

        int activeCommandCount;
        boolean commandServiceActive;
        boolean monitorConfigured;
        boolean monitorServiceActive;
        boolean eventBridgeEvidencePresent;
        long eventBridgeCapturedAt;
        boolean eventBridgeAuthenticated;
        boolean eventBridgeFallback;
        boolean eventBridgeSecurityFailure;
        String eventBridgeSummary = "";

        boolean runtimeEvidencePresent;
        boolean runtimeAvailable;
        long runtimeCapturedAt;
        String runtimeSummary = "";

        boolean processEvidencePresent;
        boolean processAvailable;
        long processCapturedAt;
        boolean processPartial;
        String processSummary = "";
    }

    static DebugHealthReport evaluate(Evidence evidence) {
        Evidence e = evidence == null ? new Evidence() : evidence;
        long now = Math.max(0L, e.now);
        List<DebugHealthReport.Item> items = new ArrayList<>();

        int rootEvidence = evidenceState(now, e.diagnosticsCapturedAt, ROOT_STALE_AFTER_MS);
        if (rootEvidence == DebugHealthReport.EVIDENCE_MISSING) {
            add(items, "root_evidence", DOMAIN_ROOT, "Root diagnostics evidence",
                "Full root diagnostics have not been cached yet.", "Root diagnostics cache",
                ACTION_ROOT, DebugHealthReport.UNKNOWN, rootEvidence, 0L);
        } else if (rootEvidence == DebugHealthReport.EVIDENCE_STALE) {
            add(items, "root_evidence", DOMAIN_ROOT, "Root diagnostics evidence",
                "Cached root diagnostics are stale and are not treated as current truth.",
                "Root diagnostics cache", ACTION_ROOT, DebugHealthReport.WARNING,
                rootEvidence, e.diagnosticsCapturedAt);
        } else {
            add(items, "root_evidence", DOMAIN_ROOT, "Root diagnostics evidence",
                "Cached root diagnostics are current.", "Root diagnostics cache", ACTION_ROOT,
                DebugHealthReport.PASS, rootEvidence, e.diagnosticsCapturedAt);
        }

        addRootDependent(items, "root_access", "Root access",
            e.rootGranted, "Root grant is available", detailOr(e.diagnosticsError, "Root access is not granted"),
            rootEvidence, e.diagnosticsCapturedAt);
        addRootDependent(items, "xbs_binaries", "XBS binaries",
            e.binariesReady, "xbs and XBSctl are executable", "xbs or XBSctl is missing or not executable",
            rootEvidence, e.diagnosticsCapturedAt);
        addRootDependent(items, "managed_storage", "Managed storage paths",
            e.storageReady, "Config, list, and backup paths are readable and writable",
            "A managed XBS path is missing, unreadable, or unwritable",
            rootEvidence, e.diagnosticsCapturedAt, ACTION_CONFIGURATION, DOMAIN_STORAGE);
        addRootDependent(items, "foreground_services", "Foreground-service declarations",
            e.foregroundServicesReady, "Foreground-service permissions and declarations are ready",
            "Foreground-service setup is incomplete",
            rootEvidence, e.diagnosticsCapturedAt, ACTION_ROOT, DOMAIN_RUNTIME);

        int compatibilityEvidence = !e.compatibilityLive
            ? DebugHealthReport.EVIDENCE_MISSING
            : evidenceState(now, e.compatibilityCapturedAt, COMPATIBILITY_STALE_AFTER_MS);
        if (!e.compatibilityLive) {
            add(items, "schema_compatibility", DOMAIN_ROOT, "Live schema compatibility",
                detailOr(e.compatibilitySummary, "Only the static offline compatibility fallback is available"),
                "Capability contract cache", ACTION_ROOT, DebugHealthReport.UNKNOWN,
                compatibilityEvidence, e.compatibilityCapturedAt);
        } else if (compatibilityEvidence == DebugHealthReport.EVIDENCE_STALE) {
            add(items, "schema_compatibility", DOMAIN_ROOT, "Live schema compatibility",
                detailOr(e.compatibilitySummary, "Live capability evidence is stale"),
                "Capability contract cache", ACTION_ROOT, DebugHealthReport.WARNING,
                compatibilityEvidence, e.compatibilityCapturedAt);
        } else if (!e.compatibilityCompatible || !e.compatibilitySchemaDigestsMatch) {
            add(items, "schema_compatibility", DOMAIN_ROOT, "Live schema compatibility",
                detailOr(e.compatibilitySummary, "The installed module contract is incompatible with the app"),
                "Capability contract cache", ACTION_ROOT, DebugHealthReport.BLOCKER,
                compatibilityEvidence, e.compatibilityCapturedAt);
        } else {
            add(items, "schema_compatibility", DOMAIN_ROOT, "Live schema compatibility",
                detailOr(e.compatibilitySummary, "The installed module contract is compatible"),
                "Capability contract cache", ACTION_ROOT, DebugHealthReport.PASS,
                compatibilityEvidence, e.compatibilityCapturedAt);
        }

        int historyEvidence = e.historyEvidencePresent
            ? evidenceState(now, e.historyCapturedAt, HISTORY_STALE_AFTER_MS)
            : DebugHealthReport.EVIDENCE_MISSING;
        int historySeverity;
        String historyDetail;
        if (historyEvidence == DebugHealthReport.EVIDENCE_MISSING) {
            historySeverity = DebugHealthReport.UNKNOWN;
            historyDetail = "No passive Room history status has been cached yet";
        } else if (historyEvidence == DebugHealthReport.EVIDENCE_STALE) {
            historySeverity = DebugHealthReport.WARNING;
            historyDetail = historyDetail(e) + ". Cached status is stale and was not refreshed automatically.";
        } else {
            historySeverity = !e.historyDatabaseReady || !e.historyMigrationComplete
                ? DebugHealthReport.BLOCKER
                : clean(e.historyError).isEmpty() ? DebugHealthReport.PASS : DebugHealthReport.WARNING;
            historyDetail = historyDetail(e);
        }
        add(items, "history_store", DOMAIN_STORAGE, "Local history store",
            historyDetail, "Passive Room history cache", ACTION_ACTIVITY, historySeverity,
            historyEvidence, e.historyCapturedAt);

        int transactionEvidence = transactionEvidence(now, e.transactionCapturedAt, e.transactionStatus);
        int transactionSeverity = transactionEvidence == DebugHealthReport.EVIDENCE_MISSING
            ? DebugHealthReport.UNKNOWN
            : transactionEvidence == DebugHealthReport.EVIDENCE_STALE
                ? DebugHealthReport.WARNING : transactionSeverity(e.transactionStatus);
        add(items, "config_transaction", DOMAIN_STORAGE, "Configuration transaction journal",
            transactionDetail(e.transactionStatus), "Transaction journal cache", ACTION_CONFIGURATION,
            transactionSeverity, transactionEvidence, e.transactionCapturedAt);

        add(items, "notifications", DOMAIN_NOTIFICATIONS, "Notification readiness",
            detailOr(e.notificationsSummary, e.notificationsReady
                ? "Notification permission, app state, and channels are ready"
                : "Notification permission, app state, or a required channel needs attention"),
            "Android notification manager", ACTION_NOTIFICATIONS,
            e.notificationsReady ? DebugHealthReport.PASS : DebugHealthReport.WARNING,
            DebugHealthReport.EVIDENCE_CURRENT, now);

        boolean commandConsistent = e.activeCommandCount == 0 || e.commandServiceActive;
        int commandSeverity = historyEvidence == DebugHealthReport.EVIDENCE_MISSING
            ? DebugHealthReport.UNKNOWN
            : historyEvidence == DebugHealthReport.EVIDENCE_STALE
                ? DebugHealthReport.WARNING
                : commandConsistent ? DebugHealthReport.PASS : DebugHealthReport.BLOCKER;
        String commandDetail = historyEvidence == DebugHealthReport.EVIDENCE_MISSING
            ? "No passive active-command count has been cached yet"
            : historyEvidence == DebugHealthReport.EVIDENCE_STALE
                ? "Cached active-command count is stale and was not refreshed automatically"
                : commandConsistent
                    ? e.activeCommandCount + " active command record(s) are service-consistent"
                    : e.activeCommandCount + " active command record(s) exist without the command service";
        add(items, "command_service", DOMAIN_RUNTIME, "Active-command consistency",
            commandDetail, "Passive Room history cache and in-process service state", ACTION_ACTIVITY,
            commandSeverity, historyEvidence, e.historyCapturedAt);

        boolean monitorConsistent = !e.monitorConfigured || e.monitorServiceActive;
        add(items, "event_monitor", DOMAIN_RUNTIME, "Event-monitor consistency",
            !e.monitorConfigured ? "The event monitor is intentionally disabled"
                : e.monitorServiceActive ? "The configured event monitor is running"
                : "The event monitor is enabled but the service is not active",
            "App settings and in-process service state", ACTION_RUNTIME,
            monitorConsistent ? DebugHealthReport.PASS : DebugHealthReport.WARNING,
            DebugHealthReport.EVIDENCE_CURRENT, now);


        int bridgeEvidence = e.eventBridgeEvidencePresent
            ? evidenceState(now, e.eventBridgeCapturedAt, EVENT_BRIDGE_STALE_AFTER_MS)
            : DebugHealthReport.EVIDENCE_MISSING;
        int bridgeSeverity;
        if (bridgeEvidence == DebugHealthReport.EVIDENCE_MISSING) bridgeSeverity = DebugHealthReport.UNKNOWN;
        else if (bridgeEvidence == DebugHealthReport.EVIDENCE_STALE) bridgeSeverity = DebugHealthReport.WARNING;
        else if (e.eventBridgeSecurityFailure) bridgeSeverity = e.monitorConfigured
            ? DebugHealthReport.BLOCKER : DebugHealthReport.WARNING;
        else if (e.eventBridgeAuthenticated) bridgeSeverity = DebugHealthReport.PASS;
        else if (e.eventBridgeFallback) bridgeSeverity = DebugHealthReport.WARNING;
        else bridgeSeverity = DebugHealthReport.UNKNOWN;
        add(items, "event_bridge", DOMAIN_OBSERVABILITY, "Authenticated event bridge",
            detailOr(e.eventBridgeSummary, "No authenticated event-bridge evidence is cached"),
            "Event bridge cache", ACTION_RUNTIME, bridgeSeverity, bridgeEvidence,
            e.eventBridgeCapturedAt);

        int runtimeEvidence = e.runtimeEvidencePresent
            ? evidenceState(now, e.runtimeCapturedAt, RUNTIME_STALE_AFTER_MS)
            : DebugHealthReport.EVIDENCE_MISSING;
        if (!e.runtimeEvidencePresent) {
            add(items, "runtime_state", DOMAIN_RUNTIME, "Runtime state evidence",
                detailOr(e.runtimeSummary, "The XBS runtime has not been sampled yet"),
                "Runtime state cache", ACTION_RUNTIME, DebugHealthReport.UNKNOWN,
                runtimeEvidence, e.runtimeCapturedAt);
        } else if (runtimeEvidence == DebugHealthReport.EVIDENCE_STALE) {
            add(items, "runtime_state", DOMAIN_RUNTIME, "Runtime state evidence",
                detailOr(e.runtimeSummary, "The cached runtime state is stale"),
                "Runtime state cache", ACTION_RUNTIME, DebugHealthReport.WARNING,
                runtimeEvidence, e.runtimeCapturedAt);
        } else {
            add(items, "runtime_state", DOMAIN_RUNTIME, "Runtime state evidence",
                detailOr(e.runtimeSummary, e.runtimeAvailable ? "The XBS runtime is available"
                    : "The XBS runtime is unavailable"),
                "Runtime state cache", ACTION_RUNTIME,
                e.runtimeAvailable ? DebugHealthReport.PASS : DebugHealthReport.BLOCKER,
                runtimeEvidence, e.runtimeCapturedAt);
        }

        int processEvidence = e.processEvidencePresent
            ? evidenceState(now, e.processCapturedAt, PROCESS_STALE_AFTER_MS)
            : DebugHealthReport.EVIDENCE_MISSING;
        if (!e.processEvidencePresent) {
            add(items, "process_visibility", DOMAIN_OBSERVABILITY, "Process visibility evidence",
                detailOr(e.processSummary, "No process sample has been cached yet"),
                "Process snapshot cache", ACTION_PROCESS, DebugHealthReport.UNKNOWN,
                processEvidence, e.processCapturedAt);
        } else if (processEvidence == DebugHealthReport.EVIDENCE_STALE) {
            add(items, "process_visibility", DOMAIN_OBSERVABILITY, "Process visibility evidence",
                detailOr(e.processSummary, "The cached process sample is stale"),
                "Process snapshot cache", ACTION_PROCESS, DebugHealthReport.WARNING,
                processEvidence, e.processCapturedAt);
        } else if (!e.processAvailable || e.processPartial) {
            add(items, "process_visibility", DOMAIN_OBSERVABILITY, "Process visibility evidence",
                detailOr(e.processSummary, !e.processAvailable
                    ? "The latest process sample is unavailable" : "The latest process sample is partial"),
                "Process snapshot cache", ACTION_PROCESS, DebugHealthReport.WARNING,
                processEvidence, e.processCapturedAt);
        } else {
            add(items, "process_visibility", DOMAIN_OBSERVABILITY, "Process visibility evidence",
                detailOr(e.processSummary, "The latest process sample is complete"),
                "Process snapshot cache", ACTION_PROCESS, DebugHealthReport.PASS,
                processEvidence, e.processCapturedAt);
        }

        return new DebugHealthReport(now, items);
    }

    private static void addRootDependent(List<DebugHealthReport.Item> items, String id,
                                         String title, boolean ready, String pass, String fail,
                                         int evidenceState, long capturedAt) {
        addRootDependent(items, id, title, ready, pass, fail, evidenceState, capturedAt,
            ACTION_ROOT, DOMAIN_ROOT);
    }

    private static void addRootDependent(List<DebugHealthReport.Item> items, String id,
                                         String title, boolean ready, String pass, String fail,
                                         int evidenceState, long capturedAt, String actionId,
                                         String domain) {
        if (evidenceState == DebugHealthReport.EVIDENCE_MISSING) {
            add(items, id, domain, title, "Current root diagnostics evidence is missing",
                "Root diagnostics cache", actionId, DebugHealthReport.UNKNOWN,
                evidenceState, capturedAt);
        } else if (evidenceState == DebugHealthReport.EVIDENCE_STALE) {
            add(items, id, domain, title, "Cached value: " + (ready ? pass : fail)
                + ". Re-run diagnostics before relying on it.",
                "Root diagnostics cache", actionId, DebugHealthReport.UNKNOWN,
                evidenceState, capturedAt);
        } else {
            add(items, id, domain, title, ready ? pass : fail, "Root diagnostics cache",
                actionId, ready ? DebugHealthReport.PASS : DebugHealthReport.BLOCKER,
                evidenceState, capturedAt);
        }
    }

    static int evidenceState(long now, long capturedAt, long maximumAge) {
        if (capturedAt <= 0L || now < capturedAt) return DebugHealthReport.EVIDENCE_MISSING;
        return now - capturedAt <= Math.max(0L, maximumAge)
            ? DebugHealthReport.EVIDENCE_CURRENT : DebugHealthReport.EVIDENCE_STALE;
    }

    static int transactionSeverity(String status) {
        String value = normalizeStatus(status);
        if (value.isEmpty() || "not_cached".equals(value) || "unknown".equals(value)) {
            return DebugHealthReport.UNKNOWN;
        }
        if ("committed".equals(value) || "rolled_back".equals(value)
            || "recovered".equals(value) || "none".equals(value)) {
            return DebugHealthReport.PASS;
        }
        if ("preparing".equals(value) || "promoting".equals(value)
            || "rollback_requested".equals(value) || "recovery_requested".equals(value)) {
            return DebugHealthReport.BLOCKER;
        }
        if (value.contains("failed") || value.startsWith("invalid") || "missing".equals(value)) {
            return DebugHealthReport.BLOCKER;
        }
        return DebugHealthReport.WARNING;
    }

    static int transactionEvidence(long now, long capturedAt, String status) {
        String value = normalizeStatus(status);
        if (value.isEmpty() || "not_cached".equals(value) || "unknown".equals(value)) {
            return DebugHealthReport.EVIDENCE_MISSING;
        }
        return evidenceState(now, capturedAt, TRANSACTION_STALE_AFTER_MS);
    }

    static String transactionDetail(String status) {
        String value = normalizeStatus(status);
        if (value.isEmpty() || "not_cached".equals(value)) {
            return "No cached transaction evidence is available";
        }
        if ("none".equals(value)) return "No configuration transaction has been recorded";
        if ("committed".equals(value)) return "The latest configuration transaction committed safely";
        if ("rolled_back".equals(value)) return "The latest configuration transaction was rolled back safely";
        if ("recovered".equals(value)) return "The latest interrupted transaction was recovered safely";
        if ("preparing".equals(value) || "promoting".equals(value)) {
            return "A configuration transaction appears incomplete: " + value;
        }
        if (value.contains("failed") || value.startsWith("invalid") || "missing".equals(value)) {
            return "Unsafe transaction evidence: " + value;
        }
        return "Latest transaction status: " + value;
    }

    private static String historyDetail(Evidence e) {
        if (!e.historyDatabaseReady) return detailOr(e.historyError, "The Room history database is unavailable");
        if (!e.historyMigrationComplete) return "Legacy command-history migration is incomplete";
        if (!clean(e.historyError).isEmpty()) return "Room history is available with warning: " + clean(e.historyError);
        return "Room history database and legacy migration are ready";
    }

    private static void add(List<DebugHealthReport.Item> items, String id, String domain,
                            String title, String detail, String source, String actionId,
                            int severity, int evidenceState, long capturedAt) {
        items.add(new DebugHealthReport.Item(id, domain, title, detail, source, actionId,
            severity, evidenceState, capturedAt));
    }

    private static String normalizeStatus(String value) {
        return clean(value).toLowerCase(Locale.US).replace('-', '_').replace(' ', '_');
    }

    private static String detailOr(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim().replaceAll(" +", " ");
        return cleaned.length() <= MAX_EVIDENCE_TEXT_CHARS ? cleaned : cleaned.substring(0, MAX_EVIDENCE_TEXT_CHARS - 1) + "…";
    }
}
