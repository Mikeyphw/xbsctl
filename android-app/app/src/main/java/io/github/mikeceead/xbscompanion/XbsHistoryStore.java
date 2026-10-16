package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Room-backed bounded history with idempotent migration from the legacy SharedPreferences payload. */
final class XbsHistoryStore {
    private static final String LEGACY_PREFS = "suite_runs";
    private static final String LEGACY_KEY = "runs";
    private static final String MIGRATION_KEY = "legacy_runs_v1";
    private static final int MAX_TRANSACTIONS = 32;
    private static final int MAX_LEGACY_JSON_CHARS = 512 * 1024;
    private static final int MAX_LEGACY_RECORDS = RunRetentionPolicy.MAX_RECORDS * 4;
    private static final String STATUS_CACHE_KEY = "history_status_v1";
    private static final int MAX_STATUS_CACHE_CHARS = 16 * 1024;
    private static final Object LOCK = new Object();
    private static volatile XbsHistoryStatus lastStatus = XbsHistoryStatus.uninitialized();
    private static List<RunRecord> emergencyRuns = new ArrayList<>();
    private static final ExecutorService DATABASE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xbs-history-room");
        thread.setDaemon(true);
        return thread;
    });

    private XbsHistoryStore() {}

    static Executor databaseExecutor() { return DATABASE_EXECUTOR; }

    static void warmUp(Context context) {
        Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            synchronized (LOCK) { ensure(app); }
        }, "xbs-history-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    static List<RunRecord> allRuns(Context context) {
        if (onMainThread()) {
            Context app = context.getApplicationContext();
            DATABASE_EXECUTOR.execute(() -> allRuns(app));
            synchronized (LOCK) {
                if (!emergencyRuns.isEmpty()) return copy(emergencyRuns);
                return legacyRuns(app).records;
            }
        }
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            try {
                XbsHistoryDatabase database = ensure(app);
                List<RunRecord> records = toRecords(database.historyDao().allRuns());
                emergencyRuns = copy(records);
                refreshStatus(app, database, "");
                return records;
            } catch (RuntimeException error) {
                markFailure(app, error);
                if (!emergencyRuns.isEmpty()) return copy(emergencyRuns);
                return legacyRuns(app).records;
            }
        }
    }

    static boolean upsertRun(Context context, RunRecord record) {
        if (record == null || !RunRecord.validRunId(record.runId)) return false;
        if (onMainThread()) {
            Context app = context.getApplicationContext();
            synchronized (LOCK) { updateEmergency(record, AppSettings.historyRetention(app)); }
            DATABASE_EXECUTOR.execute(() -> upsertRun(app, record));
            return true;
        }
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            updateEmergency(record, AppSettings.historyRetention(app));
            try {
                XbsHistoryDatabase database = ensure(app);
                database.historyDao().upsertRun(HistoryRunEntity.fromRecord(record));
                pruneRuns(database.historyDao(), AppSettings.historyRetention(app));
                refreshStatus(app, database, "");
                return true;
            } catch (RuntimeException error) {
                markFailure(app, error);
                return false;
            }
        }
    }

    static boolean setPinned(Context context, String runId, boolean pinned) {
        if (!RunRecord.validRunId(runId)) return false;
        if (onMainThread()) {
            Context app = context.getApplicationContext(); final String id = runId.trim();
            synchronized (LOCK) { for (RunRecord record : emergencyRuns) if (id.equals(record.runId)) record.pinned = pinned; }
            DATABASE_EXECUTOR.execute(() -> setPinned(app, id, pinned));
            return true;
        }
        runId = runId.trim();
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            for (RunRecord record : emergencyRuns) if (runId.equals(record.runId)) record.pinned = pinned;
            try {
                XbsHistoryDatabase database = ensure(app);
                database.historyDao().setRunPinned(runId, pinned);
                refreshStatus(app, database, "");
                return true;
            } catch (RuntimeException error) {
                markFailure(app, error);
                return false;
            }
        }
    }

    static boolean deleteFinished(Context context, String runId) {
        if (!RunRecord.validRunId(runId)) return false;
        if (onMainThread()) {
            Context app=context.getApplicationContext(); final String id=runId.trim();
            synchronized (LOCK) { emergencyRuns.removeIf(record -> id.equals(record.runId) && !RunFilter.isActive(record)); }
            DATABASE_EXECUTOR.execute(() -> deleteFinished(app, id));
            return true;
        }
        final String normalizedRunId = runId.trim();
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            emergencyRuns.removeIf(record -> normalizedRunId.equals(record.runId) && !RunFilter.isActive(record));
            try {
                XbsHistoryDatabase database = ensure(app);
                int deleted = database.historyDao().deleteFinishedRun(normalizedRunId);
                refreshStatus(app, database, "");
                return deleted > 0;
            } catch (RuntimeException error) {
                markFailure(app, error);
                return false;
            }
        }
    }

    static int deleteFinished(Context context, List<String> runIds) {
        int deleted = 0;
        if (runIds == null) return deleted;
        for (String runId : new ArrayList<>(runIds)) if (deleteFinished(context, runId)) deleted++;
        return deleted;
    }

    static void applyRetention(Context context) {
        if (onMainThread()) { Context app=context.getApplicationContext(); synchronized (LOCK) { emergencyRuns=RunRetentionPolicy.trim(emergencyRuns, AppSettings.historyRetention(app)); } DATABASE_EXECUTOR.execute(() -> applyRetention(app)); return; }
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            int limit = AppSettings.historyRetention(app);
            emergencyRuns = RunRetentionPolicy.trim(emergencyRuns, limit);
            try {
                XbsHistoryDatabase database = ensure(app);
                pruneRuns(database.historyDao(), limit);
                refreshStatus(app, database, "");
            } catch (RuntimeException error) {
                markFailure(app, error);
            }
        }
    }

    static boolean clearFinished(Context context) {
        if (onMainThread()) { Context app=context.getApplicationContext(); synchronized (LOCK) { List<RunRecord> kept=new ArrayList<>(); for(RunRecord r:emergencyRuns) if(RunFilter.isActive(r)||r.pinned) kept.add(r); emergencyRuns=copy(kept); } DATABASE_EXECUTOR.execute(() -> clearFinished(app)); return true; }
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            List<RunRecord> protectedRecords = new ArrayList<>();
            for (RunRecord record : emergencyRuns) if (RunFilter.isActive(record) || record.pinned) protectedRecords.add(record);
            emergencyRuns = copy(protectedRecords);
            try {
                XbsHistoryDatabase database = ensure(app);
                database.historyDao().clearFinishedRuns();
                refreshStatus(app, database, "");
                return true;
            } catch (RuntimeException error) {
                markFailure(app, error);
                return false;
            }
        }
    }

    static boolean clearRuns(Context context) {
        if (onMainThread()) { Context app=context.getApplicationContext(); synchronized (LOCK) { emergencyRuns=new ArrayList<>(); } DATABASE_EXECUTOR.execute(() -> clearRuns(app)); return true; }
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            emergencyRuns = new ArrayList<>();
            try {
                XbsHistoryDatabase database = ensure(app);
                database.historyDao().clearRuns();
                app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().remove(LEGACY_KEY).apply();
                refreshStatus(app, database, "");
                return true;
            } catch (RuntimeException error) {
                markFailure(app, error);
                return false;
            }
        }
    }

    static void recordTransaction(Context context, XbsConfigTransaction.Record record) {
        if (record == null || !record.exists()) return;
        if (onMainThread()) { Context app=context.getApplicationContext(); DATABASE_EXECUTOR.execute(() -> recordTransaction(app, record)); return; }
        if (!RunRecord.validRunId(record.id)) return;
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            try {
                XbsHistoryDatabase database = ensure(app);
                HistoryDao dao = database.historyDao();
                dao.upsertTransaction(HistoryTransactionEntity.fromRecord(record));
                dao.trimTransactions(MAX_TRANSACTIONS);
                refreshStatus(app, database, "");
            } catch (RuntimeException error) {
                markFailure(app, error);
            }
        }
    }

    static String latestTransactionSupport(Context context) {
        if (onMainThread()) { Context app=context.getApplicationContext(); DATABASE_EXECUTOR.execute(() -> latestTransactionSupport(app)); return ""; }
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            try {
                XbsHistoryDatabase database = ensure(app);
                HistoryTransactionEntity entity = database.historyDao().latestTransaction();
                refreshStatus(app, database, "");
                return entity == null ? "" : HistoryTransactionEntity.boundedSupportText(entity.supportText == null ? "" : entity.supportText.trim());
            } catch (RuntimeException error) {
                markFailure(app, error);
                return "";
            }
        }
    }

    static XbsHistoryStatus status(Context context) {
        if (onMainThread()) { Context app=context.getApplicationContext(); DATABASE_EXECUTOR.execute(() -> status(app)); return peekStatus(app); }
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            try {
                XbsHistoryDatabase database = ensure(app);
                refreshStatus(app, database, "");
            } catch (RuntimeException error) {
                markFailure(app, error);
            }
            return lastStatus;
        }
    }

    /** Returns cached history evidence without opening Room or attempting legacy migration. */
    static XbsHistoryStatus peekStatus(Context context) {
        if (context == null) return XbsHistoryStatus.uninitialized();
        synchronized (LOCK) {
            if (lastStatus.cached()) return lastStatus;
            String raw = XbsCache.peek(context.getApplicationContext(), STATUS_CACHE_KEY);
            if (raw == null || raw.trim().isEmpty() || raw.length() > MAX_STATUS_CACHE_CHARS) return XbsHistoryStatus.uninitialized();
            try {
                XbsHistoryStatus cached = XbsHistoryStatus.fromJson(new JSONObject(raw));
                if (cached.cached()) lastStatus = cached;
                return cached;
            } catch (Exception ignored) {
                return XbsHistoryStatus.uninitialized();
            }
        }
    }

    private static boolean onMainThread() {
        Looper main = Looper.getMainLooper();
        return main != null && Thread.currentThread() == main.getThread();
    }

    private static XbsHistoryDatabase ensure(Context app) {
        XbsHistoryDatabase database = XbsHistoryDatabase.get(app);
        migrateLegacy(app, database);
        return database;
    }

    private static void migrateLegacy(Context app, XbsHistoryDatabase database) {
        HistoryDao dao = database.historyDao();
        HistoryMetadataEntity existing = dao.metadata(MIGRATION_KEY);
        SharedPreferences legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        if (existing != null) {
            if (legacy.contains(LEGACY_KEY)) legacy.edit().remove(LEGACY_KEY).apply();
            return;
        }

        LegacyParse parsed = legacyRuns(app);
        database.runInTransaction(() -> {
            if (dao.metadata(MIGRATION_KEY) != null) return;
            if (!parsed.records.isEmpty()) {
                List<HistoryRunEntity> entities = new ArrayList<>();
                for (RunRecord record : RunRetentionPolicy.trim(parsed.records, AppSettings.historyRetention(app))) {
                    entities.add(HistoryRunEntity.fromRecord(record));
                }
                dao.upsertRuns(entities);
            }
            String value = "complete imported=" + parsed.records.size() + " skipped=" + parsed.skipped;
            dao.putMetadata(new HistoryMetadataEntity(MIGRATION_KEY, value, System.currentTimeMillis()));
        });
        legacy.edit().remove(LEGACY_KEY).apply();
    }

    private static void pruneRuns(HistoryDao dao, int limit) {
        List<HistoryRunEntity> all = dao.allRuns();
        if (all.size() <= limit) return;
        List<RunRecord> records = toRecords(all);
        Set<String> kept = new HashSet<>();
        for (RunRecord record : RunRetentionPolicy.trim(records, limit)) kept.add(record.runId);
        List<String> remove = new ArrayList<>();
        for (HistoryRunEntity entity : all) if (!kept.contains(entity.runId)) remove.add(entity.runId);
        if (!remove.isEmpty()) dao.deleteRuns(remove);
    }

    private static void updateEmergency(RunRecord record, int limit) {
        List<RunRecord> next = copy(emergencyRuns);
        next.removeIf(item -> item.runId.equals(record.runId));
        next.add(record);
        emergencyRuns = RunRetentionPolicy.trim(next, limit);
    }

    private static LegacyParse legacyRuns(Context app) {
        List<RunRecord> records = new ArrayList<>();
        int skipped = 0;
        try {
            String raw = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).getString(LEGACY_KEY, "[]");
            if (raw != null && raw.length() > MAX_LEGACY_JSON_CHARS) {
                skipped++;
                return new LegacyParse(records, skipped);
            }
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            int length = Math.min(array.length(), MAX_LEGACY_RECORDS);
            if (array.length() > MAX_LEGACY_RECORDS) skipped += array.length() - MAX_LEGACY_RECORDS;
            for (int index = 0; index < length; index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null || !hasValidStringRunId(item)) { skipped++; continue; }
                try { records.add(RunRecord.fromJson(item)); }
                catch (RuntimeException malformed) { skipped++; }
            }
        } catch (Exception malformedPayload) {
            skipped++;
        }
        return new LegacyParse(RunRetentionPolicy.trim(records), skipped);
    }

    private static List<RunRecord> toRecords(List<HistoryRunEntity> entities) {
        List<RunRecord> records = new ArrayList<>();
        if (entities != null) for (HistoryRunEntity entity : entities) if (entity != null) {
            RunRecord record = entity.toRecord();
            if (RunRecord.validRunId(record.runId)) records.add(record);
        }
        return RunRetentionPolicy.sorted(records);
    }

    private static boolean hasValidStringRunId(JSONObject item) {
        Object raw = item.opt("run_id");
        return raw instanceof String && RunRecord.validRunId((String) raw);
    }

    private static void refreshStatus(Context app, XbsHistoryDatabase database, String error) {
        HistoryDao dao = database.historyDao();
        HistoryMetadataEntity migration = dao.metadata(MIGRATION_KEY);
        int[] counts = parseMigration(migration == null ? "" : migration.value);
        lastStatus = new XbsHistoryStatus(true, migration != null, counts[0], counts[1],
            dao.runCount(), dao.activeRunCount(), dao.transactionCount(), databaseBytes(app),
            System.currentTimeMillis(), error);
        persistStatus(app);
    }

    private static void markFailure(Context app, RuntimeException error) {
        XbsHistoryStatus previous = lastStatus;
        int active = 0;
        for (RunRecord record : emergencyRuns) if (RunFilter.isActive(record)) active++;
        lastStatus = new XbsHistoryStatus(false, previous.migrationComplete,
            previous.importedLegacyRuns, previous.skippedLegacyRuns,
            emergencyRuns.size(), active, previous.transactionCount, databaseBytes(app),
            System.currentTimeMillis(), error == null ? "Unknown local history failure"
                : error.getClass().getSimpleName() + ": " + clean(error.getMessage()));
        persistStatus(app);
    }

    private static void persistStatus(Context app) {
        if (app != null && lastStatus.cached()) {
            XbsCache.put(app, STATUS_CACHE_KEY, lastStatus.toJson().toString());
        }
    }

    private static int[] parseMigration(String value) {
        int imported = 0;
        int skipped = 0;
        String[] parts = clean(value).split(" +");
        for (String part : parts) {
            if (part.startsWith("imported=")) imported = positiveInt(part.substring(9));
            if (part.startsWith("skipped=")) skipped = positiveInt(part.substring(8));
        }
        return new int[] {imported, skipped};
    }

    private static int positiveInt(String value) {
        try { return Math.max(0, Integer.parseInt(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static long databaseBytes(Context app) {
        File database = app.getDatabasePath(XbsHistoryDatabase.NAME);
        long bytes = fileBytes(database);
        bytes += fileBytes(new File(database.getPath() + "-wal"));
        bytes += fileBytes(new File(database.getPath() + "-shm"));
        return bytes;
    }

    private static long fileBytes(File file) { return file.isFile() ? Math.max(0L, file.length()) : 0L; }
    private static List<RunRecord> copy(List<RunRecord> source) { return new ArrayList<>(source == null ? new ArrayList<>() : source); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static final class LegacyParse {
        final List<RunRecord> records;
        final int skipped;
        LegacyParse(List<RunRecord> records, int skipped) { this.records = records; this.skipped = skipped; }
    }
}
