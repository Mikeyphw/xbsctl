package io.github.mikeceead.xbscompanion;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.service.quicksettings.TileService;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Rootless GX05 API36 security preflight. Never installs, invokes su, or changes daemon effects. */
@RunWith(AndroidJUnit4.class)
public final class Gx05PreflightInstrumentedTest {
    @Test public void privilegedForegroundServicesNotExported() throws Exception {
        Context app=ApplicationProvider.getApplicationContext();
        PackageManager pm=app.getPackageManager();
        for(Class<?> type:new Class<?>[]{RootCommandService.class,EventMonitorService.class}) {
            ServiceInfo info=pm.getServiceInfo(new ComponentName(app,type),0);
            assertNotNull(type.getSimpleName(),info);
            assertFalse(type.getSimpleName()+" must be private",info.exported);
        }
    }

    @Test public void notificationAndBootReceiversNotExported() throws Exception {
        Context app=ApplicationProvider.getApplicationContext();
        PackageManager pm=app.getPackageManager();
        for(Class<?> type:new Class<?>[]{NotificationActionReceiver.class,BootReceiver.class}) {
            android.content.pm.ActivityInfo info=pm.getReceiverInfo(new ComponentName(app,type),0);
            assertNotNull(type.getSimpleName(),info);
            assertFalse(type.getSimpleName()+" must be private",info.exported);
        }
    }

    @Test public void allSystemUiTilesBoundBySystemPermission() throws Exception {
        Context app=ApplicationProvider.getApplicationContext();
        for(Class<?> type:new Class<?>[]{XbsPauseTileService.class,XbsSafeModeTileService.class,
            XbsSaverTileService.class,XbsProfilesTileService.class,XbsRestoreDefaultsTileService.class}) {
            ServiceInfo info=app.getPackageManager().getServiceInfo(new ComponentName(app,type),0);
            assertTrue("SystemUI TileService must be exported",info.exported);
            assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE",info.permission);
        }
    }

    @Test public void hostileTaskerIntentWithoutAuthorizationCannotStartCommand() {
        Context app=ApplicationProvider.getApplicationContext();
        // Validate the same authorization guard the exported receiver calls;
        // never invoke goAsync() outside a real Android broadcast.
        assertFalse("Missing HMAC must fail",TaskerAuthorization.verify(app,null,false).valid);
        android.os.Bundle forged=new android.os.Bundle();
        forged.putString(TaskerRequestPolicy.KEY_HOST_PACKAGE,"net.dinglisch.android.taskerm");
        forged.putString(TaskerRequestPolicy.KEY_SIGNATURE,"not-an-hmac");
        assertFalse("Forged signature must fail",TaskerAuthorization.verify(app,forged,false).valid);
    }

    @Test public void unverifiedSnapshotCannotAuthorizeQuickTiles() {
        Context app=ApplicationProvider.getApplicationContext();
        XbsStateStore.save(app,XbsSnapshot.unavailable("GX05 emulator has no root daemon"),
            java.util.Collections.emptyList());
        EventBridgeStore.save(app,EventBridgeReport.unavailable());
        for(XbsTileDecision.Kind kind:XbsTileDecision.Kind.values())
            assertFalse("No daemon verification for "+kind,XbsTileController.requestToggle(app,kind));
    }
    @Test public void readOnlyStateReadsDoNotLeakDescriptors() {
        Context app=ApplicationProvider.getApplicationContext();
        java.io.File folder=new java.io.File("/proc/self/fd");
        String[] initial=folder.list();
        assertNotNull("App process must expose its own descriptor inventory",initial);
        for(int i=0;i<40;i++) {
            XbsStateStore.current(app);
            EventBridgeStore.cached(app);
            RuntimeNotificationStore.cached(app);
        }
        String[] after=folder.list();
        assertNotNull("App process FD inventory disappeared",after);
        assertTrue("Rootless cache reads leaked file descriptors",after.length<=initial.length+16);
    }

    @Test public void readOnlyStateReadsDoNotSpawnUnboundedWorkers() {
        Context app=ApplicationProvider.getApplicationContext();
        int before=Thread.getAllStackTraces().size();
        for(int i=0;i<40;i++) {
            XbsStateStore.current(app);
            EventBridgeStore.cached(app);
        }
        int after=Thread.getAllStackTraces().size();
        assertTrue("Read-only cache reads launched unbounded threads",after<=before+24);
    }
}
