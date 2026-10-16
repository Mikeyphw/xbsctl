package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class RuntimeNotificationPolicyTest {
    private XbsSnapshot state(boolean paused, boolean safe) {
        return new XbsSnapshot(true, safe, paused, paused ? "paused" : "normal",
            Arrays.asList("manual"), Arrays.asList("boot"), null, "{}", "", 1000L);
    }

    @Test public void stateTokenChangesAcrossRuntimeAndBridgeState() {
        EventBridgeReport bridge = new EventBridgeReport(1000L, "authenticated", "ok", "", "HMAC-SHA256",
            "0011223344556677", 4L, 900L, "aa", false, false, false, true);
        assertNotEquals(RuntimeNotificationPolicy.token(state(false, false), bridge),
            RuntimeNotificationPolicy.token(state(true, false), bridge));
    }

    @Test public void staleExpiredAndSecurityBlockedActionsAreRejected() {
        XbsSnapshot snapshot = state(false, false);
        EventBridgeReport bridge = new EventBridgeReport(1000L, "authenticated", "ok", "", "HMAC-SHA256",
            "0011223344556677", 4L, 900L, "aa", false, false, false, true);
        String token = RuntimeNotificationPolicy.token(snapshot, bridge);
        assertEquals("stale_action", RuntimeNotificationPolicy.evaluateAction("pause", "", "wrong", 1000L,
            1500L, token, bridge, false, false, snapshot.active).code);
        assertEquals("expired_action", RuntimeNotificationPolicy.evaluateAction("pause", "", token, 1L,
            RuntimeNotificationPolicy.ACTION_MAX_AGE_MS + 2L, token, bridge, false, false, snapshot.active).code);
        EventBridgeReport blocked = new EventBridgeReport(1000L, "unavailable", "bad_mac", "", "", "", 0L, 0L, "", false, false, true, false);
        assertEquals("bridge_security_failure", RuntimeNotificationPolicy.evaluateAction("pause", "", token, 1000L,
            1500L, token, blocked, false, false, snapshot.active).code);
    }

    @Test public void authenticatedActionsAndMonitorStopAreAllowedButMissingTransportBlocksMutation() {
        XbsSnapshot snapshot = state(false, false);
        EventBridgeReport bridge = new EventBridgeReport(1000L, "authenticated", "ok", "", "HMAC-SHA256",
            "0011223344556677", 4L, 900L, "aa", false, false, false, true);
        String token = RuntimeNotificationPolicy.token(snapshot, bridge);
        assertTrue(RuntimeNotificationPolicy.evaluateAction("pause", "", token, 1000L, 1500L,
            token, bridge, false, false, snapshot.active).allowed);
        EventBridgeReport unavailable = EventBridgeReport.unavailable();
        String unavailableToken = RuntimeNotificationPolicy.token(snapshot, unavailable);
        assertEquals("bridge_unavailable", RuntimeNotificationPolicy.evaluateAction("pause", "", unavailableToken,
            1000L, 1500L, unavailableToken, unavailable, false, false, snapshot.active).code);
        assertTrue(RuntimeNotificationPolicy.evaluateAction("monitor_stop", "", unavailableToken, 1000L, 1500L,
            unavailableToken, unavailable, false, false, snapshot.active).allowed);
    }

    @Test public void missingTransportNeverExposesMutationActions() {
        XbsSnapshot snapshot = state(false, false);
        assertFalse(RuntimeNotificationPolicy.mutationActionsAllowed(snapshot, EventBridgeReport.unavailable(), true));
        assertFalse(RuntimeNotificationPolicy.mutationActionsAllowed(snapshot, null, true));
        assertFalse(RuntimeNotificationPolicy.mutationActionsAllowed(snapshot, EventBridgeReport.unavailable(), false));
    }

    @Test public void monitorStopStillRequiresTheCurrentUnexpiredToken() {
        XbsSnapshot snapshot = state(false, false);
        EventBridgeReport unavailable = EventBridgeReport.unavailable();
        String token = RuntimeNotificationPolicy.token(snapshot, unavailable);
        assertEquals("stale_action", RuntimeNotificationPolicy.evaluateAction("monitor_stop", "", "old", 1000L,
            1500L, token, unavailable, false, false, snapshot.active).code);
    }
}
