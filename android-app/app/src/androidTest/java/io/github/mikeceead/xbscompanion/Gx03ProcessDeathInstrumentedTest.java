package io.github.mikeceead.xbscompanion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Stages must execute in SEPARATE host-driven instrumentations with am force-stop between. */
@RunWith(AndroidJUnit4.class)
public final class Gx03ProcessDeathInstrumentedTest {
    private static final String PREF="gx03_process_death_v1";
    private static final String RUN_ID="gx03-owned-pending-fixture";

    @Test public void stageAWriteDurablePendingOwnership() {
        Context app=ApplicationProvider.getApplicationContext();
        PendingCommandStore.clearForTest(app);
        XbsCommandSpec spec=XbsCommandSpec.fromAction(
            SuiteCatalog.find("xbs","status"),"",ExecutionSource.APP);
        assertNotNull(spec);
        PendingCommandStore.save(app,RUN_ID,9303,spec,false);
        assertTrue(PendingCommandStore.markOwned(app,RUN_ID));
        SharedPreferences prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        assertTrue("Could not durably commit stage A marker",
            prefs.edit().putString("run",RUN_ID).putInt("pid",Process.myPid())
                .putBoolean("stage_a",true).commit());
        PendingCommandStore.Recovered pending=PendingCommandStore.load(app,RUN_ID);
        assertNotNull(pending);
        assertTrue(pending.valid);
        assertTrue(pending.owned);
        assertFalse(pending.tasker);
    }

    @Test public void stageBInspectRecoveryAfterExternalForceStop() {
        Context app=ApplicationProvider.getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        assertTrue("Stage A fixture absent (package data may have been cleared)",
            prefs.getBoolean("stage_a",false));
        assertEquals(RUN_ID,prefs.getString("run",null));
        assertNotEquals("Process was not externally killed between test stages",
            prefs.getInt("pid",-1),Process.myPid());
        PendingCommandStore.Recovered pending=PendingCommandStore.load(app,RUN_ID);
        assertNotNull("Persisted pending command missing",pending);
        assertTrue("Persisted command invalid",pending.valid);
        assertTrue("Owned command unexpectedly lost",pending.owned);
        assertFalse(pending.tasker);
        // No privileged command is executed; this only inspects the persisted state.
        assertFalse(RootCommandService.isDuplicateRunForTest(RUN_ID));
        assertTrue(prefs.edit().clear().commit());
        PendingCommandStore.clearForTest(app);
    }
}
