package io.github.mikeceead.xbscompanion;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Rootless service lifecycle/ownership tests: no service is started or root command executed. */
@RunWith(AndroidJUnit4.class)
public final class R05cLifecycleInstrumentedTest {
    private Context app;
    @Before public void before() {
        app=ApplicationProvider.getApplicationContext();
        PendingCommandStore.clearForTest(app);
        RootCommandService.clearInMemoryForTest();
    }
    @After public void after() {
        PendingCommandStore.clearForTest(app);
        RootCommandService.clearInMemoryForTest();
    }

    @Test public void ownedPendingCommandSurvivesMemoryLossWithoutReplay() {
        String id="root-r05c-owned";
        XbsCommandSpec spec=XbsCommandSpec.fromAction(SuiteCatalog.find("xbs", "status"), "", ExecutionSource.APP);
        PendingCommandStore.save(app,id,42,spec,false);
        assertTrue(PendingCommandStore.markOwned(app,id));
        RootCommandService.clearInMemoryForTest();
        PendingCommandStore.Recovered r=PendingCommandStore.load(app,id);
        assertNotNull(r);
        assertTrue(r.valid);
        assertTrue(r.owned);
        assertFalse(r.tasker);
    }

    @Test public void corruptPendingCommandCannotBeReplayed() {
        String id="root-r05c-invalid";
        PendingCommandStore.corruptForTest(app,id,"{\"v\":2,\"run_id\":\"root-foreign\"}");
        PendingCommandStore.Recovered recovered=PendingCommandStore.load(app,id);
        assertNotNull(recovered);
        assertFalse(recovered.valid);
    }

    @SuppressWarnings("unchecked")
    @Test public void duplicateStartCheckSeesExistingOwner() throws Exception {
        Field field=RootCommandService.class.getDeclaredField("ACTIVE");
        field.setAccessible(true);
        ConcurrentHashMap<String,Object> active=(ConcurrentHashMap<String,Object>)field.get(null);
        String id="root-r05c-duplicate";
        try {
            active.put(id,new Object()); // Only containsKey is exercised; no service is started.
            assertTrue(RootCommandService.isDuplicateRunForTest(id));
            assertFalse(RootCommandService.isDuplicateRunForTest("root-r05c-other"));
        } finally { active.remove(id); }
    }
}
