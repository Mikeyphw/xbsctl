package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.List;

/** Compatibility facade over the Room-backed run history database. */
final class RunStore {
    static final String ACTION_UPDATED = "io.github.mikeceead.xbscompanion.RUN_UPDATED";
    private static final String LEGACY_PREFS = "suite_runs";
    private static final String LEGACY_KEY = "runs";
    private static final long STALE_ACTIVE_GRACE_MS = 60_000L;

    private RunStore() {}

    static synchronized void started(Context context, String runId, XbsCommandSpec spec) {
        RunRecord r = new RunRecord();
        r.runId=runId;
        r.module=spec.action.module;
        r.actionId=spec.action.id;
        r.title=spec.action.title;
        r.command=spec.command;
        r.backend=spec.backend;
        r.workdir=spec.workdir;
        r.source=spec.source.label;
        r.mutationClass=spec.mutationClass.label;
        r.timeoutSeconds=spec.timeout.seconds;
        r.status="running";
        r.message=spec.startMessage();
        upsert(context, r);
    }

    static synchronized void cancelling(Context context, String runId, String reason) {
        RunRecord record = find(context, runId);
        if (record == null) return;
        record.status = "cancelling";
        record.message = "Cancelling root command" + (reason == null || reason.trim().isEmpty() ? "" : " · " + reason.trim());
        upsert(context, record);
    }

    static synchronized void finish(Context context, String runId, int exitCode,
                                    String stdout, String stderr, String error) {
        RunRecord record = findRaw(context, runId);
        if (record == null) {
            record = new RunRecord();
            record.runId = runId;
        }
        record.exitCode = exitCode;
        record.stdout = limit(stdout);
        record.stderr = limit(stderr);
        record.error = limit(error);
        record.finishedAt = System.currentTimeMillis();
        MachineResult machine = MachineResult.parse(stdout);
        boolean ok = exitCode == 0 && (error == null || error.trim().isEmpty()) && (!machine.parsed || machine.ok);
        if (R05aCommandPolicy.requiresAck(record.command) && exitCode != -1) {
            R05aDaemonReceipt receipt=R05aDaemonReceipt.inspect(record.command,exitCode,stdout,error);
            record.status=receipt.historyStatus();
            record.message=receipt.applied() ? "Daemon applied and observed · "+receipt.requestId
                : "Daemon outcome not confirmed · "+receipt.code;
        } else {
            record.status=exitCode==130 ? "cancelled" : exitCode==124||exitCode==125 ? "recovery_required" : ok ? "ok" : "failed";
            record.message=ResultFormatter.summary(exitCode,stdout,stderr,error,ok?"Completed":"Failed");
        }
        upsert(context, record);
    }

    static synchronized List<RunRecord> all(Context context) {
        reconcileStaleActive(context);
        return RunRetentionPolicy.sorted(XbsHistoryStore.allRuns(context));
    }

    static synchronized RunRecord latest(Context context, String module) {
        for (RunRecord record : all(context)) {
            if (module == null || module.trim().isEmpty() || module.equals(record.module)) return record;
        }
        return null;
    }

    static synchronized RunRecord find(Context context, String id) {
        if (!RunRecord.validRunId(id)) return null;
        id = id.trim();
        for (RunRecord record : all(context)) if (id.equals(record.runId)) return record;
        return null;
    }

    static synchronized boolean isStaleActive(Context context, RunRecord record) {
        if (context == null || record == null || !RunFilter.isActive(record)) return false;
        if (RunLivenessMonitor.anyServiceOwnerActive() || RunLivenessMonitor.isRootProcessLive(record.runId)) return false;
        long age = System.currentTimeMillis() - Math.max(0L, record.startedAt);
        return age > STALE_ACTIVE_GRACE_MS;
    }

    static synchronized boolean forceFinalizeStale(Context context, String runId, String reason) {
        RunRecord record = findRaw(context, runId);
        if (!isStaleActive(context, record)) return false;
        String detail = reason == null || reason.trim().isEmpty()
            ? "Recovered stale active run after app process death" : reason.trim();
        finish(context, runId, -1, record == null ? "" : record.stdout, record == null ? "" : record.stderr, detail);
        return true;
    }

    static synchronized List<RunRecord> filtered(Context context, RunFilter filter) {
        return RunFilter.apply(all(context), filter);
    }

    static synchronized List<RunRecord> active(Context c) { return filtered(c, RunFilter.ACTIVE); }
    static synchronized int activeCount(Context context) { return active(context).size(); }

    /** Returns the last cached active-run count without opening Room or migrating legacy data. */
    static synchronized int peekActiveCount(Context context) {
        return XbsHistoryStore.peekStatus(context).activeRunCount;
    }
    static synchronized int count(Context context, RunFilter filter) { return filtered(context, filter).size(); }

    static synchronized void setPinned(Context context, String runId, boolean pinned) {
        if (!RunRecord.validRunId(runId)) return;
        runId = runId.trim();
        boolean stored = XbsHistoryStore.setPinned(context, runId, pinned);
        if (!stored) {
            List<RunRecord> records = all(context);
            for (RunRecord record : records) if (runId.equals(record.runId)) record.pinned = pinned;
            writeLegacy(context, records);
        }
        notifyChanged(context, runId);
    }

    static synchronized boolean deleteFinished(Context context, String runId) {
        if (!RunRecord.validRunId(runId)) return false;
        runId = runId.trim();
        boolean deleted = XbsHistoryStore.deleteFinished(context, runId);
        if (!deleted) {
            List<RunRecord> records = all(context);
            boolean present = false;
            for (RunRecord record : records) if (runId.equals(record.runId)) present = true;
            if (!present) { writeLegacy(context, records); deleted = true; }
        }
        if (deleted) notifyChanged(context, runId);
        return deleted;
    }

    static synchronized int deleteFinished(Context context, List<String> runIds) {
        int deleted = 0;
        if (runIds != null) for (String runId : new java.util.ArrayList<>(runIds)) if (deleteFinished(context, runId)) deleted++;
        if (deleted > 0) notifyChanged(context, "");
        return deleted;
    }

    static synchronized void applyRetention(Context context) {
        XbsHistoryStore.applyRetention(context);
        notifyChanged(context, "");
    }

    static synchronized void clearFinished(Context context) {
        if (!XbsHistoryStore.clearFinished(context)) {
            List<RunRecord> protectedRecords = new java.util.ArrayList<>();
            for (RunRecord record : all(context)) if (RunFilter.isActive(record) || record.pinned) protectedRecords.add(record);
            writeLegacy(context, protectedRecords);
        }
        notifyChanged(context, "");
    }

    static synchronized void clear(Context context) {
        XbsHistoryStore.clearRuns(context);
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().remove(LEGACY_KEY).apply();
        notifyChanged(context, "");
    }

    private static RunRecord findRaw(Context context, String id) {
        if (!RunRecord.validRunId(id)) return null;
        id = id.trim();
        for (RunRecord record : RunRetentionPolicy.sorted(XbsHistoryStore.allRuns(context))) if (id.equals(record.runId)) return record;
        return null;
    }

    private static void reconcileStaleActive(Context context) {
        if (context == null || RunLivenessMonitor.anyServiceOwnerActive()) return;
        for (RunRecord record : RunRetentionPolicy.sorted(XbsHistoryStore.allRuns(context))) {
            if (isStaleActive(context, record)) {
                finish(context, record.runId, -1, record.stdout, record.stderr,
                    "Recovered stale active run after app process death; no live root process handle remains");
            }
        }
    }

    private static void upsert(Context context, RunRecord value) {
        if (value == null || !RunRecord.validRunId(value.runId)) return;
        if (!XbsHistoryStore.upsertRun(context, value)) {
            List<RunRecord> records = XbsHistoryStore.allRuns(context);
            records.removeIf(record -> record.runId.equals(value.runId));
            records.add(value);
            writeLegacy(context, RunRetentionPolicy.trim(records));
        }
        notifyChanged(context, value.runId);
    }

    private static void writeLegacy(Context context, List<RunRecord> records) {
        JSONArray array = new JSONArray();
        for (RunRecord record : RunRetentionPolicy.trim(records, AppSettings.historyRetention(context))) {
            try { array.put(record.toJson()); }
            catch (Exception ignored) {}
        }
        SharedPreferences prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(LEGACY_KEY, array.toString()).apply();
    }

    private static void notifyChanged(Context context, String id) {
        context.sendBroadcast(new Intent(ACTION_UPDATED).setPackage(context.getPackageName()).putExtra("run_id", id));
    }

    private static String limit(String value) {
        if (value == null) return "";
        return value.length() > RunRetentionPolicy.MAX_OUTPUT_CHARS
            ? value.substring(value.length() - RunRetentionPolicy.MAX_OUTPUT_CHARS) : value;
    }
}
