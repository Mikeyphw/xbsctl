package io.github.mikeceead.xbscompanion;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PostAuditRootProcessTreeInstrumentedTest {
    @Test public void blockingDescendantDoesNotSurviveVerifiedTermination() throws Exception {
        Process probe = new ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start();
        assertTrue(probe.waitFor(5, TimeUnit.SECONDS));
        Assume.assumeTrue("root unavailable", probe.exitValue() == 0);

        String token = "it-tree-" + Long.toUnsignedString(System.nanoTime(), 36);
        String pidFile = RootProcessTree.pidFile(token);
        Process owner = new ProcessBuilder("su", "-c",
            RootProcessTree.wrap(pidFile, "sh -c 'sleep 120 & wait'"))
            .redirectErrorStream(true).start();
        Thread.sleep(500L);

        RootProcessTree.TerminationResult result = RootProcessTree.terminate(pidFile);
        assertTrue("termination status=" + result.status + " detail=" + result.detail, result.terminated);
        assertTrue("owner shell survived termination", owner.waitFor(5, TimeUnit.SECONDS));
    }
}
