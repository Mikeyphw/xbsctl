package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RootDiagnosticsReportTest {
    @Test public void readyReportHasNoFailuresOrWarnings() {
        RootDiagnosticsReport report = report(true, true, true, true, true, true, 0, false, false);
        assertEquals(0, report.failureCount());
        assertEquals(0, report.warningCount());
        assertEquals("Root runtime ready", report.headline());
        assertTrue(report.supportText().contains("command_channel=true"));
    }

    @Test public void missingRootSuppressesCascadingBinaryAndStorageFailures() {
        RootDiagnosticsReport report = report(false, false, false, true, true, true, 0, false, false);
        assertEquals(1, report.failureCount());
        assertEquals("Root access is required", report.headline());
    }

    @Test public void staleActiveRecordAndStoppedMonitorAreWarnings() {
        RootDiagnosticsReport report = report(true, true, true, true, true, false, 2, true, false);
        assertEquals(0, report.failureCount());
        assertEquals(2, report.warningCount());
        assertEquals("Root runtime ready with warnings", report.headline());
    }

    private static RootDiagnosticsReport report(boolean root, boolean binaries, boolean storage,
        boolean notifications, boolean foreground, boolean commandService, int activeRuns,
        boolean monitorConfigured, boolean monitorActive) {
        return new RootDiagnosticsReport(1000L, root, "KernelSU", "1.0", "/system/bin/su",
            binaries ? "/system/bin/xbs" : "not found", binaries,
            binaries ? "/system/bin/XBSctl" : "not found", binaries,
            storage, storage, storage, storage, storage, storage, storage, storage, storage,
            notifications, notifications, notifications, notifications, notifications,
            foreground, foreground, commandService, activeRuns, monitorConfigured, monitorActive, "");
    }
}
