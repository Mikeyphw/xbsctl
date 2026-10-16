package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public class TaskerConfigCodecPolicyTest {
    @Test public void legacyApisAcceptOnlyPinnedVerifiedHost() {
        assertTrue(TaskerConfigCodec.legacySenderPolicy(26, true));
        assertTrue(TaskerConfigCodec.legacySenderPolicy(28, true));
        assertTrue(TaskerConfigCodec.legacySenderPolicy(33, true));
        assertFalse(TaskerConfigCodec.legacySenderPolicy(26, false));
        assertFalse(TaskerConfigCodec.legacySenderPolicy(33, false));
    }
    @Test public void api34MustUsePlatformSenderIdentity() {
        assertFalse(TaskerConfigCodec.legacySenderPolicy(34, true));
        assertFalse(TaskerConfigCodec.legacySenderPolicy(36, true));
        assertFalse(TaskerConfigCodec.legacySenderPolicy(25, true));
    }
}
