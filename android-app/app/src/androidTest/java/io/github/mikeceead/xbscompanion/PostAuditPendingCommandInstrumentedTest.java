package io.github.mikeceead.xbscompanion;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PostAuditPendingCommandInstrumentedTest {
    private Context context;

    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        PendingCommandStore.clearForTest(context);
        RootCommandService.clearInMemoryForTest();
    }

    @After public void cleanup() {
        PendingCommandStore.clearForTest(context);
        RootCommandService.clearInMemoryForTest();
    }

    @Test public void persistedPendingCommandSurvivesInMemoryProcessStateLoss() {
        String runId = "root-recovery-test";
        XbsCommandSpec spec = XbsCommandSpec.fromAction(SuiteCatalog.find("xbs", "event_run"), "manual", ExecutionSource.APP);
        PendingCommandStore.save(context, runId, 42, spec, false);
        RootCommandService.clearInMemoryForTest();
        PendingCommandStore.Recovered recovered = PendingCommandStore.load(context, runId);
        assertNotNull(recovered);
        assertTrue(recovered.valid);
        assertFalse(recovered.owned);
        assertEquals(spec.command, recovered.spec.command);
    }

    @Test public void ownedCommandIsDurablyDistinguishableAndCannotBeBlindlyReexecuted() {
        String runId = "root-owned-test";
        XbsCommandSpec spec = XbsCommandSpec.fromAction(SuiteCatalog.find("xbs", "status"), "", ExecutionSource.APP);
        PendingCommandStore.save(context, runId, 43, spec, false);
        assertTrue(PendingCommandStore.markOwned(context, runId));
        RootCommandService.clearInMemoryForTest();
        PendingCommandStore.Recovered recovered = PendingCommandStore.load(context, runId);
        assertNotNull(recovered);
        assertTrue(recovered.valid);
        assertTrue(recovered.owned);
    }

    @Test public void missingAndCorruptPendingStateFailClosed() {
        assertNull(PendingCommandStore.load(context, "root-missing-test"));
        PendingCommandStore.corruptForTest(context, "root-corrupt-test", "{\"v\":2,\"run_id\":\"other\"}");
        PendingCommandStore.Recovered corrupt = PendingCommandStore.load(context, "root-corrupt-test");
        assertNotNull(corrupt);
        assertFalse(corrupt.valid);
    }
}
