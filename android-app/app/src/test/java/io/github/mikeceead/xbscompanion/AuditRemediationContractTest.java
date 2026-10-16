package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class AuditRemediationContractTest {
    @Test public void pendingOwnershipIsACheckedDurabilityOperation() throws Exception {
        Method method = PendingCommandStore.class.getDeclaredMethod("markOwned", android.content.Context.class, String.class);
        assertEquals(boolean.class, method.getReturnType());
    }

    @Test public void legacyTaskerPolicyFailsClosedWithoutPinnedHost() {
        for (int api : new int[]{26,28,33}) {
            assertTrue(TaskerConfigCodec.legacySenderPolicy(api, true));
            assertFalse(TaskerConfigCodec.legacySenderPolicy(api, false));
        }
        assertFalse(TaskerConfigCodec.legacySenderPolicy(34, true));
    }
}
