package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public final class R05cReplayCursorPolicyTest {
    private static final String K="aaaaaaaaaaaaaaaa", K2="bbbbbbbbbbbbbbbb";
    private static final String D="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String D2="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test public void refusesStaleAndEquivocatingSequences() {
        assertFalse(R05cReplayCursorPolicy.mayCommit(K, 12, D, K, 11, D2));
        assertFalse(R05cReplayCursorPolicy.mayCommit(K, 12, D, K, 12, D2));
        assertTrue(R05cReplayCursorPolicy.mayCommit(K, 12, D, K, 12, D));
        assertTrue(R05cReplayCursorPolicy.mayCommit(K, 12, D, K, 13, D2));
    }

    @Test public void authenticatedRotationAllowsSequenceReset() {
        assertTrue(R05cReplayCursorPolicy.mayCommit(K, 12, D, K2, 1, D2));
        assertTrue(R05cReplayCursorPolicy.mayCommit("", 0, "", K, 1, D));
    }

    @Test public void missingOrMalformedEvidenceRefuses() {
        assertFalse(R05cReplayCursorPolicy.mayCommit(K, 1, D, K, 0, D));
        assertFalse(R05cReplayCursorPolicy.mayCommit(K, 1, D, "bad", 2, D));
        assertFalse(R05cReplayCursorPolicy.mayCommit(K, 1, D, K, 2, "bad"));
        assertFalse(R05cReplayCursorPolicy.mayCommit("bad", 1, D, K, 2, D));
    }
}
