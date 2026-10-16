package io.github.mikeceead.xbscompanion;

final class CommandLaunchResult {
    final boolean started;
    final int executionId;
    final String runId;
    final String error;

    CommandLaunchResult(boolean started, int executionId, String error) {
        this(started, executionId, "", error);
    }

    CommandLaunchResult(boolean started, int executionId, String runId, String error) {
        this.started = started;
        this.executionId = executionId;
        this.runId = runId == null ? "" : runId;
        this.error = error == null ? "" : error;
    }
}
