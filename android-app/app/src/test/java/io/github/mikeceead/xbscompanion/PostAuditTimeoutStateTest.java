package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class PostAuditTimeoutStateTest {
    @Test public void bothVerifiedAndUnverifiedTimeoutsCarryDurableJournalState() {
        XbsConfigTransaction.Record durable = new XbsConfigTransaction.Record(
            "tx", "rolling_back", 1L, 2L, true, true, 0, "", Collections.emptyList(), "");
        for (int code : new int[]{124, 125}) {
            XbsRootApi.CommandOutput out = XbsConfigTransaction.withTimeoutStateForTest(
                new XbsRootApi.CommandOutput(code, "", "timeout"), durable);
            assertEquals(code, out.exitCode);
            assertTrue(out.error.contains("durable transaction state=rolling_back"));
        }
    }
}
