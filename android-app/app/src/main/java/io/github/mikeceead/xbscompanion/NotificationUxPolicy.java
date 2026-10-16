package io.github.mikeceead.xbscompanion;

import java.util.Collection;

/** Pure notification behavior policy shared by Android wiring and unit tests. */
final class NotificationUxPolicy {
    static final int RESULTS_OFF = 0;
    static final int RESULTS_FAILURES_ONLY = 1;
    static final int RESULTS_ALL = 2;
    static final long ACTION_DEDUPE_WINDOW_MS = 1500L;

    private NotificationUxPolicy() {}

    static int normalizeResultMode(int mode) {
        return mode >= RESULTS_OFF && mode <= RESULTS_ALL ? mode : RESULTS_FAILURES_ONLY;
    }

    static int nextResultMode(int mode) {
        return (normalizeResultMode(mode) + 1) % 3;
    }

    static String resultModeLabel(int mode) {
        switch (normalizeResultMode(mode)) {
            case RESULTS_OFF: return "Off";
            case RESULTS_ALL: return "All completions";
            default: return "Failures, timeouts and cancellations";
        }
    }

    static boolean isActive(String status) {
        return "running".equals(status) || "cancelling".equals(status);
    }

    static boolean isFailureLike(String status, int exitCode) {
        return "failed".equals(status) || exitCode == 124 || exitCode == 130;
    }

    static boolean shouldPostTerminal(int mode, String status, int exitCode) {
        if (isActive(status)) return false;
        int normalized = normalizeResultMode(mode);
        if (normalized == RESULTS_OFF) return false;
        return normalized == RESULTS_ALL || isFailureLike(status, exitCode);
    }

    static String terminalLabel(String status, int exitCode) {
        if (exitCode == 124) return "Timed out";
        if (exitCode == 130) return "Cancelled";
        if ("ok".equals(status)) return "Completed";
        if ("failed".equals(status)) return "Failed";
        return "Finished";
    }

    static boolean allowRuntimeAction(String actionId, String event, boolean paused, boolean safeMode,
                                      Collection<String> activeEvents) {
        if ("pause".equals(actionId)) return !paused;
        if ("resume".equals(actionId)) return paused;
        if ("safe_on".equals(actionId)) return !safeMode;
        if ("safe_off".equals(actionId)) return safeMode;
        if ("event_stop".equals(actionId)) {
            return event != null && !event.trim().isEmpty()
                && activeEvents != null && activeEvents.contains(event);
        }
        return false;
    }

    static String actionKey(String actionId, String event, String runId) {
        return safe(actionId) + '\n' + safe(event) + '\n' + safe(runId);
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
