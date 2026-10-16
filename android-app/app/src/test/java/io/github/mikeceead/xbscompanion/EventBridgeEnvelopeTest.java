package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public final class EventBridgeEnvelopeTest {
    @Test public void cliUsageMachineOutputIsAvailabilityFallbackNotSecurityFailure() {
        EventBridgeEnvelope envelope = EventBridgeEnvelope.parse(
            "usage: xbs [-h] [--json] {event-bridge,status}\n" +
            "xbs: error: unrecognized arguments: --json\n");
        assertFalse(envelope.authenticated);
        assertEquals("verifier_usage", envelope.reasonCode);
        assertTrue(EventBridgePolicy.fallbackAllowed(envelope.reasonCode));
        assertFalse(EventBridgePolicy.securityFailure(envelope.reasonCode));
    }

    @Test public void jsonUsageObjectIsAvailabilityFallback() {
        EventBridgeEnvelope envelope = EventBridgeEnvelope.parse(
            "{\"usage\":\"xbs --json event-bridge\",\"error\":\"unrecognized arguments: --json\"}");
        assertFalse(envelope.authenticated);
        assertEquals("verifier_usage", envelope.reasonCode);
        assertTrue(EventBridgePolicy.fallbackAllowed(envelope.reasonCode));
    }

    @Test public void stringBridgeUsageObjectIsAvailabilityFallback() {
        EventBridgeEnvelope envelope = EventBridgeEnvelope.parse(
            "{\"ok\":false,\"data\":{\"bridge\":\"usage: xbs [-h] {event-bridge,status}\"}}");
        assertFalse(envelope.authenticated);
        assertEquals("verifier_usage", envelope.reasonCode);
        assertTrue(EventBridgePolicy.fallbackAllowed(envelope.reasonCode));
    }

    @Test public void wrongJsonTypeWithoutUsageFailsClosed() {
        EventBridgeEnvelope envelope = EventBridgeEnvelope.parse(
            "{\"ok\":true,\"data\":{\"bridge\":\"plain text\"}}");
        assertFalse(envelope.authenticated);
        assertEquals("invalid_machine_output", envelope.reasonCode);
        assertFalse(EventBridgePolicy.fallbackAllowed(envelope.reasonCode));
    }

    @Test public void randomNonJsonMachineOutputStillFailsClosed() {
        EventBridgeEnvelope envelope = EventBridgeEnvelope.parse("not a verifier envelope");
        assertFalse(envelope.authenticated);
        assertEquals("invalid_machine_output", envelope.reasonCode);
        assertFalse(EventBridgePolicy.fallbackAllowed(envelope.reasonCode));
        assertTrue(EventBridgePolicy.securityFailure(envelope.reasonCode));
    }

    @Test public void verifiedCommandPrefersModuleBinaryAndWrapsNonJsonUsageAsJson() {
        String command = EventBridgeReader.verifiedCommand();
        assertBeforeSystemFallback(command, "run_xbs_bridge '/data/adb/modules/XtremeBS/system/bin/xbs'");
        assertBeforeSystemFallback(command, "run_xbs_bridge '/data/adb/modules_update/XtremeBS/system/bin/xbs'");
        assertBeforeSystemFallback(command, "run_xbs_bridge '/data/adb/ksu/modules/XtremeBS/system/bin/xbs'");
        assertBeforeSystemFallback(command, "run_xbs_bridge '/data/adb/ksu/modules_update/XtremeBS/system/bin/xbs'");
        assertTrue(command.contains("case \"$OUT\" in \\{*)"));
        assertTrue(command.contains("reason_code\":\"verifier_usage"));
        assertTrue(command.contains("adaptive fallback will poll legacy runtime state"));
    }

    @Test public void topLevelVerifierErrorsPreserveFallbackReasonCode() {
        EventBridgeEnvelope envelope = EventBridgeEnvelope.parse(
            "{\"ok\":false,\"reason_code\":\"missing\",\"error\":\"bridge envelope missing\"}");
        assertFalse(envelope.authenticated);
        assertEquals("missing", envelope.reasonCode);
        assertTrue(EventBridgePolicy.fallbackAllowed(envelope.reasonCode));
    }

    @Test public void authenticatedMetadataUsesStrictJsonTypes() {
        String payload = "{\"ok\":true,\"data\":{\"bridge\":{\"authenticated\":true,"
            + "\"algorithm\":\"hmac-sha256\",\"key_id\":\"0123456789abcdef\","
            + "\"sequence\":\"7\",\"issued_at\":1800000000,"
            + "\"envelope_digest\":\"" + "a".repeat(64) + "\","
            + "\"payload\":{\"event\":{},\"status\":{}}}}}";
        EventBridgeEnvelope envelope = EventBridgeEnvelope.parse(payload);
        assertFalse(envelope.authenticated);
        assertEquals("invalid_machine_output", envelope.reasonCode);
        assertFalse(EventBridgePolicy.fallbackAllowed(envelope.reasonCode));
    }

    @Test public void authenticatedBooleansUseStrictJsonTypes() {
        EventBridgeEnvelope envelope = EventBridgeEnvelope.parse(
            "{\"ok\":\"true\",\"data\":{\"bridge\":{\"authenticated\":true}}}");
        assertFalse(envelope.authenticated);
        assertEquals("invalid_machine_output", envelope.reasonCode);
    }

    private static void assertBeforeSystemFallback(String shell, String moduleCandidate) {
        String systemCandidate = "run_xbs_bridge '/system/bin/xbs'";
        int moduleIndex = shell.indexOf(moduleCandidate);
        int systemIndex = shell.lastIndexOf(systemCandidate);
        assertTrue("missing module candidate " + moduleCandidate, moduleIndex >= 0);
        assertTrue("missing explicit system fallback", systemIndex >= 0);
        assertTrue("module candidate should be probed before explicit system fallback", moduleIndex < systemIndex);
    }
}
