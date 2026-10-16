package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class XbsRootApiTerminationContractTest {
    @Test public void terminationShellVerifiesDescendantsAfterTermAndKill() {
        String shell = RootProcessTree.buildTerminationShellForTest("/data/local/tmp/xbscompanion-runs/test.pid");
        assertTrue(shell.contains("signal_all(){ SIG=$1"));
        assertTrue(shell.contains("signal_all TERM"));
        assertTrue(shell.contains("signal_all KILL"));
        assertTrue(shell.contains("alive_any"));
        assertTrue(shell.contains("/proc/$X/task/$X/children"));
        assertTrue(shell.contains("XBS_TERM=still_running"));
        assertTrue(shell.contains("XBS_TERM=terminated"));
    }

    @Test public void timeoutStatusesAreExplicitAndNonAmbiguous() {
        RootProcessTree.TerminationResult ok = new RootProcessTree.TerminationResult(true, "terminated", "");
        RootProcessTree.TerminationResult bad = new RootProcessTree.TerminationResult(false, "still_running", "survived");
        assertTrue(ok.terminated);
        assertEquals("terminated", ok.status);
        assertFalse(bad.terminated);
        assertEquals("still_running", bad.status);
    }
}
