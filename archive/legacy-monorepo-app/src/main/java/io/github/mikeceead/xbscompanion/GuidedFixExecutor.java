package io.github.mikeceead.xbscompanion;

import android.content.Context;

/** Executes only a user-confirmed, allowlisted guided fix. */
final class GuidedFixExecutor {
    private GuidedFixExecutor() {}

    static GuidedFixResult execute(Context context, GuidedFixPolicy.Spec spec) {
        long started = System.currentTimeMillis();
        if (context == null || spec == null || !spec.executable()) {
            return result(spec, false, false, true, started, "No executable guided fix was selected");
        }
        Context app = context.getApplicationContext();
        try {
            if (GuidedFixPolicy.REFRESH_ROOT_EVIDENCE.equals(spec.action)) {
                RootDiagnosticsReport report = RootDiagnosticsProbe.collect(app, true);
                boolean ok = report.rootGranted && report.error.trim().isEmpty();
                return result(spec, ok, true, !ok, started,
                    ok ? "Root and compatibility evidence refreshed" : detail(report.error, "Root evidence could not be refreshed"));
            }
            if (GuidedFixPolicy.REPAIR_STORAGE.equals(spec.action)) {
                XbsRootApi.CommandOutput output = RootDiagnosticsProbe.repairStoragePaths(app);
                String message = output.exitCode == 0 ? output.stdout : output.error.isEmpty() ? output.stdout : output.error;
                if (output.exitCode == 0) RootDiagnosticsProbe.collect(app, false);
                return result(spec, output.exitCode == 0, output.exitCode == 0, output.exitCode != 0,
                    started, detail(message, output.exitCode == 0 ? "Configuration initialized and verified" : "Configuration initialization or repair failed"));
            }
            if (GuidedFixPolicy.INITIALIZE_HISTORY.equals(spec.action)) {
                XbsHistoryStatus status = XbsHistoryStore.status(app);
                RunStore.applyRetention(app);
                status = XbsHistoryStore.status(app);
                boolean ok = status.databaseReady && status.migrationComplete;
                return result(spec, ok, true, !ok, started,
                    ok ? "Local history initialized and passive evidence cached" : detail(status.error, "Local history remains unavailable"));
            }
            if (GuidedFixPolicy.RECOVER_TRANSACTION.equals(spec.action)) {
                XbsConfigTransaction.Record record = XbsConfigApi.latestTransaction(app);
                if (record == null || !record.exists()) {
                    return result(spec, true, false, false, started, "No configuration transaction is recorded");
                }
                if (!record.needsRecovery()) {
                    return result(spec, true, false, false, started,
                        record.canRollback() ? "Latest transaction is committed; automatic rollback was intentionally not performed"
                            : "Latest transaction does not require recovery: " + record.displayStatus());
                }
                XbsConfigApi.Result recovered = XbsConfigApi.recoverLatest(app, record);
                return result(spec, recovered.ok, recovered.ok, !recovered.ok, started,
                    detail(recovered.message, recovered.ok ? "Configuration transaction recovered" : "Transaction recovery failed"));
            }
            if (GuidedFixPolicy.REPAIR_NOTIFICATIONS.equals(spec.action)) {
                new NotificationController(app);
                NotificationController.Readiness readiness = NotificationController.readiness(app);
                boolean ok = readiness.permissionGranted && readiness.appEnabled
                    && readiness.commandProgressChannelReady && readiness.commandResultsChannelReady
                    && readiness.runtimeChannelReady && readiness.alertsChannelReady;
                return result(spec, ok, true, !ok, started,
                    ok ? "Notification channels are ready"
                        : "Channels were recreated where possible; Android notification settings still require user action");
            }
            if (GuidedFixPolicy.RESTART_MONITOR.equals(spec.action)) {
                if (!AppSettings.monitorEnabled(app)) {
                    return result(spec, false, false, true, started,
                        "Event monitoring is intentionally disabled; enable it in Runtime monitoring settings first");
                }
                EventMonitorService.start(app);
                return result(spec, true, true, false, started, "Event monitor start requested");
            }
            return result(spec, false, false, true, started, "Unsupported guided fix action");
        } catch (SecurityException error) {
            return result(spec, false, false, true, started, detail(error.getMessage(), "Android denied the guided fix"));
        } catch (RuntimeException error) {
            return result(spec, false, false, true, started, detail(error.getMessage(), error.getClass().getSimpleName()));
        }
    }

    private static GuidedFixResult result(GuidedFixPolicy.Spec spec, boolean ok, boolean changed,
                                          boolean userAction, long started, String message) {
        GuidedFixResult result = new GuidedFixResult(spec == null ? "" : spec.checkId,
            spec == null ? "unknown" : spec.action, ok, changed, userAction,
            started, System.currentTimeMillis(), message);
        return result;
    }

    private static String detail(String value, String fallback) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
