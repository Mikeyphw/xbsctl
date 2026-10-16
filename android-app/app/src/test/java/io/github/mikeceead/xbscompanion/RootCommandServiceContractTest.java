package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class RootCommandServiceContractTest {
    @Test public void foregroundServiceUsesDedicatedNotificationId() {
        assertEquals(NotificationController.COMMAND_SERVICE_ID, RootCommandService.FOREGROUND_ID);
        assertNotEquals(NotificationController.STATUS_ID, RootCommandService.FOREGROUND_ID);
    }

    @Test public void runAndCancelActionsAreExplicitPackageActions() {
        assertEquals("io.github.mikeceead.xbscompanion.RUN_ROOT_COMMAND", RootCommandService.ACTION_RUN);
        assertEquals("io.github.mikeceead.xbscompanion.CANCEL_ROOT_COMMAND", RootCommandService.ACTION_CANCEL);
        assertEquals("run_id", RootCommandService.EXTRA_RUN_ID);
    }

    @Test public void commandLaunchResultCarriesRunId() {
        CommandLaunchResult result = new CommandLaunchResult(true, 7, "root-7", "");
        assertTrue(result.started);
        assertEquals(7, result.executionId);
        assertEquals("root-7", result.runId);
        assertEquals("", result.error);
    }
}
