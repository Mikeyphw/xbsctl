package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.List;

/** In-memory filter for the persisted command history list. */
enum RunFilter {
    ALL("All", "No command history", "Manual, notification and Tasker actions appear here."),
    ACTIVE("Active", "No active commands", "Foreground XBS commands appear here while they are running."),
    SUCCEEDED("Succeeded", "No successful runs", "Successful root commands will appear here."),
    FAILED("Failed", "No failed runs", "Failed commands excluding timeout and cancellation appear here."),
    TIMED_OUT("Timed out", "No timed-out runs", "Commands that hit their timeout deadline appear here."),
    CANCELLED("Cancelled", "No cancelled runs", "Commands cancelled from the app or notification appear here."),
    OUTPUT("Output", "No captured output", "Runs with stdout, stderr or error output appear here.");

    final String label;
    final String emptyTitle;
    final String emptyMessage;

    RunFilter(String label, String emptyTitle, String emptyMessage) {
        this.label = label;
        this.emptyTitle = emptyTitle;
        this.emptyMessage = emptyMessage;
    }

    boolean matches(RunRecord record) {
        if (record == null) return false;
        switch (this) {
            case ACTIVE: return isActive(record);
            case SUCCEEDED: return "ok".equals(record.status);
            case FAILED: return "failed".equals(record.status) && record.exitCode != 124 && record.exitCode != 130;
            case TIMED_OUT: return record.exitCode == 124 || status(record).equals("timeout") || status(record).equals("timed_out");
            case CANCELLED: return record.exitCode == 130 || status(record).equals("cancelled") || status(record).equals("canceled");
            case OUTPUT: return hasText(record.stdout) || hasText(record.stderr) || hasText(record.error);
            case ALL:
            default: return true;
        }
    }

    static List<RunRecord> apply(List<RunRecord> records, RunFilter filter) {
        RunFilter safe = filter == null ? ALL : filter;
        List<RunRecord> out = new ArrayList<>();
        if (records == null) return out;
        for (RunRecord record : records) if (safe.matches(record)) out.add(record);
        return out;
    }

    static RunFilter fromOrdinal(int ordinal) {
        RunFilter[] filters = values();
        return ordinal >= 0 && ordinal < filters.length ? filters[ordinal] : ALL;
    }

    static boolean isActive(RunRecord record) {
        return record != null && ("running".equals(record.status) || "cancelling".equals(record.status));
    }

    static boolean isRetryable(RunRecord record) {
        return RunRetryPolicy.canRetry(record);
    }

    private static String status(RunRecord record) {
        return record == null || record.status == null ? "" : record.status.trim().toLowerCase(java.util.Locale.US);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
