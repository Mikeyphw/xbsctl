package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public final class EventBridgePolicyTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String KEY_A = "0123456789abcdef";
    private static final String KEY_B = "fedcba9876543210";
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);

    @Test public void acceptsFreshMonotonicSnapshot() {
        EventBridgePolicy.Decision result = EventBridgePolicy.evaluate(NOW, 8, NOW - 1000,
            KEY_A, DIGEST_B, 100, 7, KEY_A, DIGEST_A);
        assertTrue(result.accepted); assertFalse(result.duplicate); assertEquals("accepted", result.code);
    }

    @Test public void acceptsExactDuplicateWithoutReplaying() {
        EventBridgePolicy.Decision result = EventBridgePolicy.evaluate(NOW, 7, NOW - 1000,
            KEY_A, DIGEST_A, 100, 7, KEY_A, DIGEST_A);
        assertTrue(result.accepted); assertTrue(result.duplicate); assertEquals("duplicate", result.code);
    }

    @Test public void rejectsReplayAndEquivocation() {
        assertEquals("replay", EventBridgePolicy.evaluate(NOW, 6, NOW - 1000,
            KEY_A, DIGEST_A, 100, 7, KEY_A, DIGEST_A).code);
        assertEquals("equivocation", EventBridgePolicy.evaluate(NOW, 7, NOW - 1000,
            KEY_A, DIGEST_B, 100, 7, KEY_A, DIGEST_A).code);
    }

    @Test public void acceptsVerifiedKeyRotationWithResetSequence() {
        EventBridgePolicy.Decision result = EventBridgePolicy.evaluate(NOW, 1, NOW - 1000,
            KEY_B, DIGEST_B, 100, 91, KEY_A, DIGEST_A);
        assertTrue(result.accepted); assertTrue(result.keyRotated); assertEquals("key_rotated", result.code);
    }

    @Test public void rejectsStaleFutureAndOversizedEvidence() {
        assertEquals("stale_envelope", EventBridgePolicy.evaluate(NOW, 8,
            NOW - EventBridgePolicy.MAX_AGE_MS - 1, KEY_A, DIGEST_B, 100, 7, KEY_A, DIGEST_A).code);
        assertEquals("future_timestamp", EventBridgePolicy.evaluate(NOW, 8,
            NOW + EventBridgePolicy.MAX_FUTURE_SKEW_MS + 1, KEY_A, DIGEST_B, 100, 7, KEY_A, DIGEST_A).code);
        assertEquals("oversized_payload", EventBridgePolicy.evaluate(NOW, 8, NOW,
            KEY_A, DIGEST_B, EventBridgePolicy.MAX_PAYLOAD_CHARS + 1, 7, KEY_A, DIGEST_A).code);
    }

    @Test public void onlyAvailabilityAndFreshnessCodesMayFallback() {
        for (String code : new String[] {"missing", "unreadable", "xbs_missing", "verifier_unavailable",
                "python_unavailable", "publication_unavailable", "bridge_not_published", "stale_envelope", "verifier_usage"}) {
            assertTrue(code, EventBridgePolicy.fallbackAllowed(code));
            assertFalse(code, EventBridgePolicy.securityFailure(code));
        }
        for (String code : new String[] {"bad_mac", "invalid_mac", "key_mismatch", "replay", "equivocation",
                "future_timestamp", "invalid_digest", "invalid_key_id", "invalid_payload", "invalid_envelope",
                "invalid_json", "invalid_encoding", "invalid_sequence", "invalid_timestamp", "invalid_key",
                "key_permissions", "symlink_rejected", "oversized", "unsupported_algorithm", "unsupported_format",
                "invalid_machine_output", "invalid_verified_snapshot", "cursor_persist_failed", "missing_bridge", "unknown"}) {
            assertFalse(code, EventBridgePolicy.fallbackAllowed(code));
            assertTrue(code, EventBridgePolicy.securityFailure(code));
        }
    }
}
