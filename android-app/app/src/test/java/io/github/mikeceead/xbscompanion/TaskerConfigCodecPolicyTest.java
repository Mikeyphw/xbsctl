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
    @Test public void completeSenderIdentityRequiredOnModernApis() {
        String expected = "net.dinglisch.android.taskerm";
        for (int api : new int[]{34,36}) {
            assertTrue(TaskerSenderPolicy.matches(api,expected,12000,expected,12000,false));
            assertFalse(TaskerSenderPolicy.matches(api,expected,-1,expected,12000,true));
            assertFalse(TaskerSenderPolicy.matches(api,"",12000,expected,12000,true));
            assertFalse(TaskerSenderPolicy.matches(api,expected,12001,expected,12000,true));
            assertFalse(TaskerSenderPolicy.matches(api,"evil.example",12000,expected,12000,true));
            assertFalse(TaskerSenderPolicy.matches(api,expected,12000,expected,-1,true));
        }
    }
}
