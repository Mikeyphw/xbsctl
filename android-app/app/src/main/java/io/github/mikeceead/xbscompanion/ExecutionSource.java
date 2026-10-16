package io.github.mikeceead.xbscompanion;

/** Origin of a root command request. Kept explicit so future run history and policies do not guess from UI state. */
enum ExecutionSource {
    APP("app"),
    NOTIFICATION("notification"),
    TASKER("tasker"),
    BOOT("boot"),
    UNKNOWN("unknown");

    final String label;

    ExecutionSource(String label) {
        this.label = label;
    }

    static ExecutionSource fromTaskerIntent(android.content.Intent taskerIntent) {
        return taskerIntent == null ? APP : TASKER;
    }
}
