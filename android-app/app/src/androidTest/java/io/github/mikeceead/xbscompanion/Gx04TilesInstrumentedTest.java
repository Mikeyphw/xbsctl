package io.github.mikeceead.xbscompanion;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.core.app.ActivityScenario;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.service.quicksettings.TileService;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real API36 package/manifest and rootless tile safety tests. No privileged effects. */
@RunWith(AndroidJUnit4.class)
public final class Gx04TilesInstrumentedTest {
    private static final Class<?>[] TILES={XbsPauseTileService.class,XbsSafeModeTileService.class,
        XbsSaverTileService.class,XbsProfilesTileService.class,XbsRestoreDefaultsTileService.class};
    private static int countAddTileButtons(View node) {
        int found=0;
        if(node instanceof Button) {
            CharSequence text=((Button)node).getText();
            if(text!=null && text.toString().startsWith("Add ") &&
                text.toString().endsWith(" tile"))found++;
        }
        if(node instanceof ViewGroup){
            ViewGroup group=(ViewGroup)node;
            for(int i=0;i<group.getChildCount();i++)found+=countAddTileButtons(group.getChildAt(i));
        }
        return found;
    }
    @Test public void allFiveTilesHaveSystemOnlyManifestPermission() throws Exception {
        Context app=ApplicationProvider.getApplicationContext();
        PackageManager manager=app.getPackageManager();
        for(Class<?> type:TILES){
            ServiceInfo info=manager.getServiceInfo(new ComponentName(app,type),PackageManager.GET_META_DATA);
            assertNotNull(type.getSimpleName(),info);
            assertTrue("SystemUI must be able to bind tile",info.exported);
            assertEquals("Tile binding permission", "android.permission.BIND_QUICK_SETTINGS_TILE", info.permission);
            assertNotNull("ACTIVE_TILE metadata required",info.metaData);
            assertTrue("ACTIVE_TILE value missing",info.metaData.getBoolean("android.service.quicksettings.ACTIVE_TILE"));
        }
    }
    @Test public void allTileServicesAreBoundToQuickSettingsAction() {
        Context app=ApplicationProvider.getApplicationContext();
        Intent query=new Intent(TileService.ACTION_QS_TILE).setPackage(app.getPackageName());
        java.util.List<android.content.pm.ResolveInfo> matches=app.getPackageManager().queryIntentServices(query,PackageManager.GET_META_DATA);
        for(Class<?> type:TILES){
            boolean found=false;
            for(android.content.pm.ResolveInfo match:matches)if(match.serviceInfo!=null &&
                type.getName().equals(match.serviceInfo.name))found=true;
            assertTrue("QS_TILE action missing for "+type.getSimpleName(),found);
        }
    }
    @Test public void unsafeAndUnconfiguredTilesNeverClaimActive() {
        assertEquals(XbsTileDecision.Phase.UNAVAILABLE,XbsTileDecision.evaluate(
            XbsTileDecision.Kind.PAUSE,false,false,false,false,false,false).phase);
        assertEquals(XbsTileDecision.Phase.PENDING,XbsTileDecision.evaluate(
            XbsTileDecision.Kind.SAFE,true,false,false,true,false,false).phase);
        assertEquals(XbsTileDecision.Phase.RECOVERY_REQUIRED,XbsTileDecision.evaluate(
            XbsTileDecision.Kind.SAFE,true,false,true,false,false,true).phase);
        assertNull(XbsTileDecision.event(XbsTileDecision.Kind.SAVER,false,false,false,false,
            false,false,false).action);
        assertNull(XbsTileDecision.evaluate(XbsTileDecision.Kind.RESTORE,true,false,false,
            false,false,false).action);
    }
    @Test public void noConfiguredRestoreRootActionAndAllEventActionsAllowlisted() {
        assertNull("No unreviewed default-reset CLI",SuiteCatalog.find("xbs","restore_defaults"));
        assertNotNull(SuiteCatalog.find("xbs","event_run"));
        assertNotNull(SuiteCatalog.find("xbs","event_stop"));
        assertNotNull(SuiteCatalog.find("xbs","pause"));
        assertNotNull(SuiteCatalog.find("xbs","resume"));
        assertFalse("No generic root action",XbsTileController.requestToggle(
            ApplicationProvider.getApplicationContext(),XbsTileDecision.Kind.RESTORE));
    }
    @Test public void hostileEventNamesRejectedBeforeLaunch() {
        assertFalse(XbsTileConfiguration.valid(";reboot"));
        assertFalse(XbsTileConfiguration.valid("foo bar"));
        assertFalse(XbsTileConfiguration.valid("a\nb"));
        assertFalse(XbsTileConfiguration.valid(""));
        assertTrue(XbsTileConfiguration.valid("low_battery"));
    }
    @Test public void verifiedCompletionRequiresTerminalHistoryAndNewerEvidence() {
        assertEquals(XbsTileCompletionPolicy.Outcome.WAIT,
            XbsTileCompletionPolicy.evaluate(null,true,true,true));
        for(String status:new String[]{"running","pending","queued"})
            assertEquals(status,XbsTileCompletionPolicy.Outcome.WAIT,
                XbsTileCompletionPolicy.evaluate(status,true,true,true));
        assertEquals(XbsTileCompletionPolicy.Outcome.WAIT,
            XbsTileCompletionPolicy.evaluate("ok",false,true,true));
        assertEquals(XbsTileCompletionPolicy.Outcome.WAIT,
            XbsTileCompletionPolicy.evaluate("ok",true,false,true));
        assertEquals(XbsTileCompletionPolicy.Outcome.WAIT,
            XbsTileCompletionPolicy.evaluate("ok",true,true,false));
        assertEquals(XbsTileCompletionPolicy.Outcome.CONFIRMED,
            XbsTileCompletionPolicy.evaluate("ok",true,true,true));
        assertEquals(XbsTileCompletionPolicy.Outcome.FAILED,
            XbsTileCompletionPolicy.evaluate("failed",true,true,true));
        assertEquals(XbsTileCompletionPolicy.Outcome.RECOVERY_REQUIRED,
            XbsTileCompletionPolicy.evaluate("recovery_required",true,true,true));
    }
    @Test public void unverifiedTilesCannotLaunchRootCommands() {
        Context app=ApplicationProvider.getApplicationContext();
        XbsStateStore.save(app,XbsSnapshot.unavailable("GX04 test - no daemon"),
            java.util.Collections.emptyList());
        EventBridgeStore.save(app,EventBridgeReport.unavailable());
        for(XbsTileDecision.Kind kind:XbsTileDecision.Kind.values())
            assertFalse("Unverified tile must not launch: "+kind,
                XbsTileController.requestToggle(app,kind));
    }
    @Test public void runtimeSettingsActivityRendersAllFiveTileRequests() {
        try(ActivityScenario<XbsTileSettingsActivity> scenario=
                ActivityScenario.launch(XbsTileSettingsActivity.class)) {
            scenario.onActivity(activity->{
                ViewGroup content=activity.findViewById(android.R.id.content);
                assertNotNull(content);
                assertEquals("Five real Add Tile buttons",5,countAddTileButtons(content));
            });
        }
    }
    @Test public void durablePendingBlocksRepeatedTapAcrossReload() {
        Context app=ApplicationProvider.getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences("gx04_tile_pending_v1_PAUSE",Context.MODE_PRIVATE);
        long started=System.currentTimeMillis();
        assertTrue(prefs.edit().clear().putString("action","pause")
            .putString("run_id","gx04-hosted-fixture-missing")
            .putLong("started",started).putBoolean("target",true)
            .putLong("bridge_sequence",100L).commit());
        try {
            assertEquals(XbsTileDecision.Phase.PENDING,
                XbsTileController.observe(app,XbsTileDecision.Kind.PAUSE).phase);
            // Reload from the persistent store rather than trusting any service instance.
            assertEquals("pause",app.getSharedPreferences("gx04_tile_pending_v1_PAUSE",
                Context.MODE_PRIVATE).getString("action",""));
            assertFalse("A second tap must not start another command",
                XbsTileController.requestToggle(app,XbsTileDecision.Kind.PAUSE));
        } finally {
            assertTrue("Hosted fixture cleanup",prefs.edit().clear().commit());
        }
    }

}
