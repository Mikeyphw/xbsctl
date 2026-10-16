package io.github.mikeceead.xbscompanion;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PostAuditRoomMainThreadInstrumentedTest {
    @Test public void serviceHistoryPathsDoNotOpenRoomSynchronouslyOnMainThread() {
        Context context = ApplicationProvider.getApplicationContext();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                RunRecord record = new RunRecord();
                record.runId = "root-main-thread-test";
                record.status = "running";
                record.startedAt = System.currentTimeMillis();
                XbsHistoryStore.upsertRun(context, record);
                XbsHistoryStore.allRuns(context);
                XbsHistoryStore.status(context);
                RunStore.active(context); // RootCommandService.ensureForeground() reaches this path.
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        assertNull("main-thread history path threw: " + failure.get(), failure.get());
    }
}
