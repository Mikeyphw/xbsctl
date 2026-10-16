package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public final class NotificationUxPolicyTest {
    @Test public void resultModesAreNormalizedAndCycle() {
        assertEquals(NotificationUxPolicy.RESULTS_FAILURES_ONLY, NotificationUxPolicy.normalizeResultMode(-9));
        assertEquals(NotificationUxPolicy.RESULTS_ALL, NotificationUxPolicy.nextResultMode(NotificationUxPolicy.RESULTS_FAILURES_ONLY));
        assertEquals(NotificationUxPolicy.RESULTS_OFF, NotificationUxPolicy.nextResultMode(NotificationUxPolicy.RESULTS_ALL));
        assertEquals("Failures, timeouts and cancellations", NotificationUxPolicy.resultModeLabel(99));
    }

    @Test public void activeRunsNeverCreateDuplicatePerRunNotifications() {
        assertFalse(NotificationUxPolicy.shouldPostTerminal(NotificationUxPolicy.RESULTS_ALL, "running", -1));
        assertFalse(NotificationUxPolicy.shouldPostTerminal(NotificationUxPolicy.RESULTS_ALL, "cancelling", -1));
    }

    @Test public void failuresOnlyIncludesFailureTimeoutAndCancellation() {
        int mode = NotificationUxPolicy.RESULTS_FAILURES_ONLY;
        assertFalse(NotificationUxPolicy.shouldPostTerminal(mode, "ok", 0));
        assertTrue(NotificationUxPolicy.shouldPostTerminal(mode, "failed", 1));
        assertTrue(NotificationUxPolicy.shouldPostTerminal(mode, "failed", 124));
        assertTrue(NotificationUxPolicy.shouldPostTerminal(mode, "failed", 130));
    }

    @Test public void allAndOffModesAreHonored() {
        assertTrue(NotificationUxPolicy.shouldPostTerminal(NotificationUxPolicy.RESULTS_ALL, "ok", 0));
        assertFalse(NotificationUxPolicy.shouldPostTerminal(NotificationUxPolicy.RESULTS_OFF, "failed", 1));
    }

    @Test public void terminalLabelsUseExitSemantics() {
        assertEquals("Completed", NotificationUxPolicy.terminalLabel("ok", 0));
        assertEquals("Timed out", NotificationUxPolicy.terminalLabel("failed", 124));
        assertEquals("Cancelled", NotificationUxPolicy.terminalLabel("failed", 130));
        assertEquals("Failed", NotificationUxPolicy.terminalLabel("failed", 2));
    }

    @Test public void staleRuntimeActionsAreRejected() {
        assertFalse(NotificationUxPolicy.allowRuntimeAction("pause", "", true, false, Arrays.asList("game")));
        assertFalse(NotificationUxPolicy.allowRuntimeAction("resume", "", false, false, Arrays.asList("game")));
        assertFalse(NotificationUxPolicy.allowRuntimeAction("safe_on", "", false, true, Arrays.asList("game")));
        assertFalse(NotificationUxPolicy.allowRuntimeAction("event_stop", "missing", false, false, Arrays.asList("game")));
    }

    @Test public void currentRuntimeActionsRemainAllowed() {
        assertTrue(NotificationUxPolicy.allowRuntimeAction("pause", "", false, false, Arrays.asList("game")));
        assertTrue(NotificationUxPolicy.allowRuntimeAction("resume", "", true, false, Arrays.asList("game")));
        assertTrue(NotificationUxPolicy.allowRuntimeAction("safe_off", "", false, true, Arrays.asList("game")));
        assertTrue(NotificationUxPolicy.allowRuntimeAction("event_stop", "game", false, false, Arrays.asList("game")));
    }

    @Test public void duplicateNotificationActionsAreDebounced() {
        String key = "phase17-unique-action";
        assertTrue(NotificationActionReceiver.accept(key, 1000L));
        assertFalse(NotificationActionReceiver.accept(key, 1200L));
        assertTrue(NotificationActionReceiver.accept(key, 3000L));
    }

    @Test public void dynamicIdNamespacesAreStableAndSeparated() {
        assertEquals(NotificationIds.run("root-123"), NotificationIds.run("root-123"));
        assertNotEquals(NotificationIds.run("root-123"), NotificationIds.event("root-123"));
        assertNotEquals(NotificationIds.event("event-1"), NotificationIds.request("event", "event-1"));
        assertNotEquals(NotificationController.STATUS_ID, NotificationIds.run("root-123"));
        assertNotEquals(NotificationController.COMMAND_SERVICE_ID, NotificationIds.event("event-1"));
    }
}
