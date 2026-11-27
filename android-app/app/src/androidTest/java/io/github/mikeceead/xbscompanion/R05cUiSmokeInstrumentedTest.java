package io.github.mikeceead.xbscompanion;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real API36 activity launch only. No user actions, root commands or device mutations. */
@RunWith(AndroidJUnit4.class)
public final class R05cUiSmokeInstrumentedTest {
    @Test public void launcherActivityReachesResumedWithoutTaskerOrRoot() {
        try (ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)) {
            assertEquals(Lifecycle.State.RESUMED, activity.getState());
        }
    }
}
