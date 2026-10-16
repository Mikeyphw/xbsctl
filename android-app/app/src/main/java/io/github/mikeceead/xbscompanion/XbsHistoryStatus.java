package io.github.mikeceead.xbscompanion;

import org.json.JSONObject;

/** Immutable evidence about the Room-backed local history store. */
final class XbsHistoryStatus {
    static final int CACHE_FORMAT = 1;
    static final int MAX_ERROR_CHARS = 700;

    final boolean databaseReady;
    final boolean migrationComplete;
    final int importedLegacyRuns;
    final int skippedLegacyRuns;
    final int runCount;
    final int activeRunCount;
    final int transactionCount;
    final long databaseBytes;
    final long capturedAt;
    final String error;

    XbsHistoryStatus(boolean databaseReady, boolean migrationComplete, int importedLegacyRuns,
                     int skippedLegacyRuns, int runCount, int transactionCount,
                     long databaseBytes, String error) {
        this(databaseReady, migrationComplete, importedLegacyRuns, skippedLegacyRuns,
            runCount, 0, transactionCount, databaseBytes, System.currentTimeMillis(), error);
    }

    XbsHistoryStatus(boolean databaseReady, boolean migrationComplete, int importedLegacyRuns,
                     int skippedLegacyRuns, int runCount, int activeRunCount,
                     int transactionCount, long databaseBytes, long capturedAt, String error) {
        this.databaseReady = databaseReady;
        this.migrationComplete = migrationComplete;
        this.importedLegacyRuns = Math.max(0, importedLegacyRuns);
        this.skippedLegacyRuns = Math.max(0, skippedLegacyRuns);
        this.runCount = Math.max(0, runCount);
        this.activeRunCount = Math.max(0, activeRunCount);
        this.transactionCount = Math.max(0, transactionCount);
        this.databaseBytes = Math.max(0L, databaseBytes);
        this.capturedAt = Math.max(0L, capturedAt);
        this.error = oneLine(error, MAX_ERROR_CHARS);
    }

    static XbsHistoryStatus uninitialized() {
        return new XbsHistoryStatus(false, false, 0, 0, 0, 0, 0, 0L, 0L,
            "Local history status has not been cached");
    }

    boolean cached() { return capturedAt > 0L; }
    boolean readyForRelease() { return databaseReady && migrationComplete && error.isEmpty(); }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("format", CACHE_FORMAT);
            json.put("databaseReady", databaseReady);
            json.put("migrationComplete", migrationComplete);
            json.put("importedLegacyRuns", importedLegacyRuns);
            json.put("skippedLegacyRuns", skippedLegacyRuns);
            json.put("runCount", runCount);
            json.put("activeRunCount", activeRunCount);
            json.put("transactionCount", transactionCount);
            json.put("databaseBytes", databaseBytes);
            json.put("capturedAt", capturedAt);
            json.put("error", error);
        } catch (Exception ignored) {}
        return json;
    }

    static XbsHistoryStatus fromJson(JSONObject json) {
        if (json == null || json.optInt("format", 0) != CACHE_FORMAT) return uninitialized();
        return new XbsHistoryStatus(
            json.optBoolean("databaseReady", false),
            json.optBoolean("migrationComplete", false),
            json.optInt("importedLegacyRuns", 0),
            json.optInt("skippedLegacyRuns", 0),
            json.optInt("runCount", 0),
            json.optInt("activeRunCount", 0),
            json.optInt("transactionCount", 0),
            json.optLong("databaseBytes", 0L),
            json.optLong("capturedAt", 0L),
            oneLine(json.optString("error"), MAX_ERROR_CHARS));
    }

    String supportText() {
        StringBuilder out = new StringBuilder(320);
        out.append("database_ready=").append(databaseReady)
            .append(" migration_complete=").append(migrationComplete)
            .append(" captured_at=").append(capturedAt).append('\n');
        out.append("runs=").append(runCount)
            .append(" active_runs=").append(activeRunCount)
            .append(" transactions=").append(transactionCount)
            .append(" database_bytes=").append(databaseBytes).append('\n');
        out.append("legacy_imported=").append(importedLegacyRuns)
            .append(" legacy_skipped=").append(skippedLegacyRuns).append('\n');
        if (!error.isEmpty()) out.append("error=").append(oneLine(error)).append('\n');
        return out.toString();
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String oneLine(String value) {
        return clean(value).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').replaceAll(" +", " ");
    }

    private static String oneLine(String value, int maxChars) {
        String normalized = oneLine(value);
        int limit = Math.max(0, maxChars);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }
}
