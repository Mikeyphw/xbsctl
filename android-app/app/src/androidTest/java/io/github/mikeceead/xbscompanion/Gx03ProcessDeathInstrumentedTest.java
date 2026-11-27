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

    private static SharedPreferences tile(Context app,XbsTileDecision.Kind kind) {
        return app.getSharedPreferences("gx04_tile_pending_v1_"+kind.name(),Context.MODE_PRIVATE);
    }
    private static void resetTiles(Context app) {
        for(XbsTileDecision.Kind kind:XbsTileDecision.Kind.values()) assertTrue(tile(app,kind).edit().clear().commit());
        XbsStateStore.save(app,XbsSnapshot.unavailable("Hosted rootless fixture"),(XbsEvent)null);
        EventBridgeStore.save(app,EventBridgeReport.unavailable());
    }
    @Test public void stageAWriteDurablePendingOwnership() {
        Context app=ApplicationProvider.getApplicationContext();
        PendingCommandStore.clearForTest(app);
        resetTiles(app);
        long now=System.currentTimeMillis();
        assertTrue(tile(app,XbsTileDecision.Kind.PAUSE).edit().putString("action","pause").putLong("started",now)
            .putString("run_id","gx03-tile-no-executed-run").putBoolean("target",true).commit());
        assertTrue(tile(app,XbsTileDecision.Kind.SAFE).edit().putString("action","safe_on").putLong("started",now-121000L).commit());
        assertTrue(tile(app,XbsTileDecision.Kind.SAVER).edit().putLong("failed_at",now).commit());
        assertEquals(XbsTileDecision.Phase.PENDING,XbsTileController.observe(app,XbsTileDecision.Kind.PAUSE).phase);
        assertFalse(XbsTileController.requestToggle(app,XbsTileDecision.Kind.PAUSE));
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
        try {
            int before=RunStore.all(app).size();
            // The persisted pending marker survived am force-stop. Unknown daemon
            // state must not make a second tap authorize any root command.
            assertEquals("pause",tile(app,XbsTileDecision.Kind.PAUSE).getString("action", ""));
            assertEquals(XbsTileDecision.Phase.PENDING,XbsTileController.observe(app,XbsTileDecision.Kind.PAUSE).phase);
            assertFalse(XbsTileController.requestToggle(app,XbsTileDecision.Kind.PAUSE));
            assertEquals(XbsTileDecision.Phase.RECOVERY_REQUIRED,XbsTileController.observe(app,XbsTileDecision.Kind.SAFE).phase);
            assertTrue(XbsTileController.recoveryNeedsReview(app,XbsTileDecision.Kind.SAFE));
            assertFalse(XbsTileController.requestToggle(app,XbsTileDecision.Kind.SAFE));
            assertEquals(XbsTileDecision.Phase.FAILED,XbsTileController.observe(app,XbsTileDecision.Kind.SAVER).phase);
            assertFalse(XbsTileController.requestToggle(app,XbsTileDecision.Kind.SAVER));
            assertEquals(XbsTileDecision.Phase.INACTIVE,XbsTileController.observe(app,XbsTileDecision.Kind.RESTORE).phase);
            assertFalse(XbsTileController.requestToggle(app,XbsTileDecision.Kind.RESTORE));
            assertEquals(before,RunStore.all(app).size());
            // Synthetic external observations exercise the real persistence and
            // decision code, NOT the authenticated transport or a rooted daemon.
            resetTiles(app);
            long observedAt=System.currentTimeMillis();
            java.util.List<String> empty=java.util.Collections.emptyList();
            XbsStateStore.save(app,new XbsSnapshot(true,false,true,"paused",empty,empty,null,"{}","",observedAt),(XbsEvent)null);
            assertEquals(XbsTileDecision.Phase.UNAVAILABLE,XbsTileController.observe(app,XbsTileDecision.Kind.PAUSE).phase);
            EventBridgeStore.save(app,EventBridgeReport.authenticated(observedAt,"fixture","0123456789abcdef",1,observedAt,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",false,false));
            assertEquals(XbsTileDecision.Phase.ACTIVE,XbsTileController.observe(app,XbsTileDecision.Kind.PAUSE).phase);
            XbsStateStore.save(app,new XbsSnapshot(true,false,false,"normal",empty,empty,null,"{}","",observedAt),(XbsEvent)null);
            assertEquals(XbsTileDecision.Phase.INACTIVE,XbsTileController.observe(app,XbsTileDecision.Kind.PAUSE).phase);
            EventBridgeStore.save(app,EventBridgeReport.unavailable());
            assertEquals(XbsTileDecision.Phase.UNAVAILABLE,XbsTileController.observe(app,XbsTileDecision.Kind.PAUSE).phase);
            assertEquals(before,RunStore.all(app).size());
        } finally {resetTiles(app);}
        assertTrue(prefs.edit().clear().commit());
        PendingCommandStore.clearForTest(app);
    }
}
