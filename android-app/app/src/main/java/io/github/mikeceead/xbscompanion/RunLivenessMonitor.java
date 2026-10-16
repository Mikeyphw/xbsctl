package io.github.mikeceead.xbscompanion;

/** Keeps foreground/root liveness checks outside the Room history facade. */
final class RunLivenessMonitor {
    private RunLivenessMonitor() {}

    static boolean anyServiceOwnerActive() { return RootCommandService.isServiceActive(); }

    static boolean isRootProcessLive(String runId) { return RootCommandRunner.isRunning(runId); }
}
