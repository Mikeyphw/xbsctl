package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Builds a bounded, copy/share-safe diagnostic snapshot for support. */
final class SupportBundleBuilder {
    static final int MAX_RUNS = 24;
    static final int MAX_EVENTS = 12;
    static final int MAX_FIELD_CHARS = 700;
    static final int MAX_COMMAND_CHARS = 320;
    static final int MAX_BUNDLE_CHARS = 128000;
    static final int SETTINGS_DESTINATION_COUNT = 8;
    static final int FORMAT_VERSION = 18;

    private SupportBundleBuilder() {}

    static String build(Context context) {
        Context app = context.getApplicationContext();
        StringBuilder out = new StringBuilder(8192);
        appendHeader(app, out);
        appendProblems(app, out);
        appendReleaseReadiness(app, out);
        appendFinalReleaseSeal(app, out);
        appendDebugHealth(app, out);
        appendCompatibility(app, out);
        appendRoot(app, out);
        appendDiagnostics(app, out);
        appendNotifications(app, out);
        appendRuntimeNotification(app, out);
        appendExternalEntry(app, out);
        appendSecuritySeal(app, out);
        appendAdaptivePerformance(app, out);
        appendSettings(app, out);
        appendConfigTransactions(app, out);
        appendLocalHistory(app, out);
        appendEventBridge(app, out);
        appendRuntime(app, out);
        appendProcesses(app, out);
        appendRuns(app, out);
        appendEvents(app, out);
        appendGuidedFix(app, out);
        SupportRedactor.Result redacted = SupportRedactor.redact(oneLinePreservingSections(out.toString(), MAX_BUNDLE_CHARS), app.getPackageName());
        String bundle = redacted.text + "\n[Redaction]\n" + redacted.evidence() + "\n";
        return bundle.length() <= MAX_BUNDLE_CHARS ? bundle : bundle.substring(0, MAX_BUNDLE_CHARS - 32) + "\n... support bundle truncated\n";
    }

    private static void appendHeader(Context context, StringBuilder out) {
        PackageInfo info = packageInfo(context);
        out.append("XBS Companion support bundle v").append(FORMAT_VERSION).append('\n');
        out.append("Generated: ").append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date())).append('\n');
        out.append("Package: ").append(context.getPackageName()).append('\n');
        if (info != null) {
            out.append("App version: ").append(safe(info.versionName)).append(" (").append(versionCode(info)).append(")\n");
        }
        out.append("Android SDK: ").append(Build.VERSION.SDK_INT).append('\n');
        out.append("Device: ").append(safe(Build.MANUFACTURER)).append(' ').append(safe(Build.MODEL)).append('\n');
    }

    private static void appendProblems(Context context, StringBuilder out) {
        out.append("\n[Problems]\n");
        out.append(DiagnosticProblemGraph.supportText(context));
    }

    private static void appendReleaseReadiness(Context context, StringBuilder out) {
        out.append("\n[Release readiness]\n");
        out.append(ReleaseReadinessEvaluator.evaluate(context).supportText());
    }

    private static void appendFinalReleaseSeal(Context context, StringBuilder out) {
        out.append("\n[Final release seal]\n");
        out.append(FinalReleaseSealStore.evaluate(context).supportText());
    }

    private static void appendDebugHealth(Context context, StringBuilder out) {
        out.append("\n[Debug health]\n");
        out.append(DebugHealthStore.cached(context).supportText());
    }

    private static void appendRoot(Context context, StringBuilder out) {
        RootStatus root = XbsRootApi.cachedRootStatus(context);
        out.append("\n[Root]\n");
        out.append("checked=").append(root.checked).append(" granted=").append(root.granted).append('\n');
        out.append("manager=").append(safe(root.manager)).append(" uid=").append(root.uid.isEmpty() ? "unknown" : safe(root.uid)).append('\n');
        out.append("xbs_installed=").append(root.xbsInstalled).append(" xbsctl_installed=").append(root.xbsctlInstalled).append('\n');
        out.append("summary=").append(oneLine(root.summary(), MAX_FIELD_CHARS)).append('\n');
    }


    private static void appendCompatibility(Context context, StringBuilder out) {
        out.append("\n[Compatibility]\n");
        out.append(XbsCompatibilityStore.cached(context).supportText());
    }

    private static void appendDiagnostics(Context context, StringBuilder out) {
        RootDiagnosticsReport report = RootDiagnosticsStore.cached(context);
        out.append("\n[Diagnostics]\n");
        out.append(report.supportText());
    }

    private static void appendNotifications(Context context, StringBuilder out) {
        NotificationController.Readiness readiness = NotificationController.readiness(context);
        out.append("\n[Notifications]\n");
        out.append("permission=").append(readiness.permissionGranted)
            .append(" app_enabled=").append(readiness.appEnabled).append('\n');
        out.append("command_progress_channel=").append(readiness.commandProgressChannelReady)
            .append(" command_results_channel=").append(readiness.commandResultsChannelReady)
            .append(" runtime_channel=").append(readiness.runtimeChannelReady)
            .append(" alerts_channel=").append(readiness.alertsChannelReady).append('\n');
        out.append("command_result_mode=")
            .append(oneLine(AppSettings.commandResultNotificationModeLabel(context), MAX_FIELD_CHARS))
            .append(" detailed_runtime=").append(AppSettings.detailedStatusNotification(context)).append('\n');
        out.append("active_command_model=single_consolidated_foreground_notification\n");
    }

    private static void appendRuntimeNotification(Context context, StringBuilder out) {
        out.append("\n[Runtime notification 2.0]\n");
        out.append(RuntimeNotificationStore.cached(context).supportText());
    }

    private static void appendExternalEntry(Context context, StringBuilder out) {
        out.append("\n[External entry hardening]\n");
        out.append(ExternalEntryStore.cached(context).supportText());
    }

    private static void appendSecuritySeal(Context context, StringBuilder out) {
        out.append("\n[Manifest path and stale-action security seal]\n");
        out.append(SecuritySealStore.cached(context).supportText());
    }

    private static void appendAdaptivePerformance(Context context, StringBuilder out) {
        out.append("\n[Accessibility adaptive layout and performance]\n");
        out.append("width_class=").append(AdaptiveLayoutPolicy.classify(context.getResources().getConfiguration().screenWidthDp)).append('\n');
        out.append("touch_target_dp=48 edge_to_edge=true predictive_back=true\n");
        out.append(UiQualityStore.cached(context).supportText());
        out.append(PerformanceStore.cached(context).supportText());
    }

    private static void appendSettings(Context context, StringBuilder out) {
        out.append("\n[Settings]\n");
        out.append("monitor_enabled=").append(AppSettings.monitorEnabled(context))
            .append(" start_on_boot=").append(AppSettings.startOnBoot(context))
            .append(" poll_seconds=").append(AppSettings.pollSeconds(context)).append('\n');
        out.append("detailed_runtime_notification=").append(AppSettings.detailedStatusNotification(context))
            .append(" command_result_mode=").append(AppSettings.commandResultNotificationMode(context))
            .append(" toast_mode=").append(AppSettings.toastMode(context)).append('\n');
        out.append("tasker_events=").append(AppSettings.taskerEventsEnabled(context))
            .append(" dynamic_colors=").append(AppSettings.useMonet(context))
            .append(" history_retention=").append(AppSettings.historyRetention(context)).append('\n');
        out.append("settings_destinations=").append(SETTINGS_DESTINATION_COUNT)
            .append(" searchable=true non_sensitive=true\n");
        out.append("settings_destination_ids=").append(settingsDestinationIds()).append('\n');
    }

    private static String settingsDestinationIds() {
        StringBuilder ids = new StringBuilder();
        int count = 0;
        for (SettingsDestination destination : SettingsCatalog.all()) {
            if (destination == null || destination.id.trim().isEmpty()) continue;
            if (ids.length() > 0) ids.append(',');
            ids.append(oneLine(destination.id, 64));
            count++;
            if (count >= SETTINGS_DESTINATION_COUNT) break;
        }
        return ids.toString();
    }

    private static void appendConfigTransactions(Context context, StringBuilder out) {
        out.append("\n[Config transactions]\n");
        out.append(XbsConfigTransactionStore.supportText(context));
    }

    private static void appendLocalHistory(Context context, StringBuilder out) {
        out.append("\n[Local history]\n");
        out.append(XbsHistoryStore.status(context).supportText());
    }

    private static void appendEventBridge(Context context, StringBuilder out) {
        out.append("\n[Event bridge]\n");
        out.append(EventBridgeStore.cached(context).supportText());
    }

    private static void appendRuntime(Context context, StringBuilder out) {
        XbsSnapshot snapshot = XbsStateStore.current(context);
        out.append("\n[Runtime]\n");
        out.append("available=").append(snapshot.available).append(" mode=").append(safe(snapshot.mode)).append(" paused=").append(snapshot.paused).append(" safe_mode=").append(snapshot.safeMode).append('\n');
        out.append("headline=").append(oneLine(snapshot.headline(), MAX_FIELD_CHARS)).append('\n');
        out.append("summary=").append(oneLine(snapshot.summary(), MAX_FIELD_CHARS)).append('\n');
        out.append("active_count=").append(snapshot.active.size()).append(" inactive_count=").append(snapshot.inactive.size()).append('\n');
        out.append("active=").append(oneLine(XbsSnapshot.join(snapshot.active), MAX_FIELD_CHARS)).append('\n');
        if (!snapshot.error.isEmpty()) out.append("error=").append(oneLine(snapshot.error, MAX_FIELD_CHARS)).append('\n');
        if (snapshot.capturedAt > 0) out.append("captured_at=").append(snapshot.capturedAt).append('\n');
    }

    private static void appendProcesses(Context context, StringBuilder out) {
        ProcessSnapshot snapshot = ProcessRepository.cached(context);
        out.append("\n[Process monitor]\n");
        out.append("available=").append(snapshot.rootAvailable)
            .append(" captured_at=").append(snapshot.capturedAt)
            .append(" processes=").append(snapshot.processes.size())
            .append(" cpu_count=").append(snapshot.diagnostics.cpuCount)
            .append('\n');
        if (snapshot.rootAvailable) {
            out.append("visibility=").append(oneLine(snapshot.diagnostics.summary(), MAX_FIELD_CHARS)).append('\n');
        } else {
            out.append("error=").append(oneLine(snapshot.error, MAX_FIELD_CHARS)).append('\n');
            if (snapshot.diagnostics.scanned > 0) {
                out.append("visibility=").append(oneLine(snapshot.diagnostics.summary(), MAX_FIELD_CHARS)).append('\n');
            }
        }
    }

    private static void appendRuns(Context context, StringBuilder out) {
        List<RunRecord> runs = RunStore.all(context);
        out.append("\n[Runs]\n");
        int pinned = 0;
        for (RunRecord run : runs) if (run != null && run.pinned) pinned++;
        out.append("total=").append(runs.size())
            .append(" active=").append(RunStore.count(context, RunFilter.ACTIVE))
            .append(" ok=").append(RunStore.count(context, RunFilter.SUCCEEDED))
            .append(" failed=").append(RunStore.count(context, RunFilter.FAILED))
            .append(" timed_out=").append(RunStore.count(context, RunFilter.TIMED_OUT))
            .append(" cancelled=").append(RunStore.count(context, RunFilter.CANCELLED))
            .append(" pinned=").append(pinned)
            .append(" retention=").append(AppSettings.historyRetention(context))
            .append('\n');
        int limit = Math.min(MAX_RUNS, runs.size());
        for (int i = 0; i < limit; i++) appendRun(out, i + 1, runs.get(i));
        if (runs.size() > limit) out.append("... ").append(runs.size() - limit).append(" older run(s) omitted\n");
    }

    private static void appendRun(StringBuilder out, int index, RunRecord run) {
        out.append('\n').append("Run #").append(index).append('\n');
        out.append("id=").append(oneLine(run.runId, 96)).append(" status=").append(safe(run.status)).append(" exit=").append(run.exitCode).append(" pinned=").append(run.pinned).append('\n');
        out.append("title=").append(oneLine(run.title, MAX_FIELD_CHARS)).append('\n');
        out.append("source=").append(safe(run.source)).append(" mutation=").append(safe(run.mutationClass)).append(" timeout=").append(run.timeoutSeconds).append("s\n");
        out.append("started_at=").append(run.startedAt).append(" finished_at=").append(run.finishedAt).append('\n');
        out.append("command=").append(oneLine(run.command, MAX_COMMAND_CHARS)).append('\n');
        out.append("message=").append(oneLine(run.message, MAX_FIELD_CHARS)).append('\n');
        appendOutput(out, "stdout", run.stdout);
        appendOutput(out, "stderr", run.stderr);
        appendOutput(out, "error", run.error);
    }

    private static void appendEvents(Context context, StringBuilder out) {
        List<XbsEvent> events = XbsStateStore.events(context);
        out.append("\n[Events]\n");
        out.append("total=").append(events.size()).append('\n');
        int limit = Math.min(MAX_EVENTS, events.size());
        for (int i = 0; i < limit; i++) {
            XbsEvent event = events.get(i);
            out.append("event#").append(i + 1)
                .append(" type=").append(safe(event.type))
                .append(" important=").append(event.isImportant())
                .append(" at=").append(event.timestamp)
                .append(" message=").append(oneLine(event.message, MAX_FIELD_CHARS))
                .append('\n');
        }
        if (events.size() > limit) out.append("... ").append(events.size() - limit).append(" older event(s) omitted\n");
    }

    private static void appendGuidedFix(Context context, StringBuilder out) {
        out.append("\n[Guided fix]\n");
        out.append(GuidedFixStore.cached(context).supportText());
    }

    private static void appendOutput(StringBuilder out, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        out.append(label).append("_chars=").append(value.length()).append('\n');
        out.append(label).append("_tail=").append(oneLine(tail(value, MAX_FIELD_CHARS), MAX_FIELD_CHARS)).append('\n');
    }

    private static long versionCode(PackageInfo info) {
        if (info == null) return 0L;
        if (Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
        return legacyVersionCode(info);
    }

    @SuppressWarnings("deprecation")
    private static long legacyVersionCode(PackageInfo info) { return info.versionCode; }

    private static PackageInfo packageInfo(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            if (Build.VERSION.SDK_INT >= 33) return pm.getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            return legacyPackageInfo(pm, context.getPackageName());
        } catch (Exception ignored) { return null; }
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo legacyPackageInfo(PackageManager pm, String name) throws PackageManager.NameNotFoundException {
        return pm.getPackageInfo(name, 0);
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private static String oneLine(String value, int max) {
        String cleaned = safe(value).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').replaceAll(" +", " ");
        if (cleaned.length() <= max) return cleaned;
        return cleaned.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String tail(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(value.length() - max);
    }

    private static String oneLinePreservingSections(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, Math.max(0, max - 48)) + "\n... support evidence truncated before redaction\n";
    }
}
