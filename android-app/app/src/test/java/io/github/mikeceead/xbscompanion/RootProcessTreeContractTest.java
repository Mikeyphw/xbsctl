package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class RootProcessTreeContractTest {
    @Test public void sharedTerminationShellTracksDescendantsAndVerifiesBothSignals() {
        String shell = RootProcessTree.buildTerminationShellForTest("/data/local/tmp/xbscompanion-runs/test.pid");
        assertTrue(shell.contains("/proc/$X/task/$X/children"));
        assertTrue(shell.contains("collect_group"));
        assertTrue(shell.contains("signal_all(){ SIG=$1"));
        assertTrue(shell.contains("signal_all TERM"));
        assertTrue(shell.contains("signal_all KILL"));
        assertTrue(shell.contains("alive_any"));
        assertTrue(shell.contains("XBS_TERM=still_running"));
        assertTrue(shell.contains("XBS_TERM=terminated"));
    }

    @Test public void pidFilesRejectUnsafeTokens() {
        assertEquals("/data/local/tmp/xbscompanion-runs/root-abc_123.pid", RootProcessTree.pidFile("root-abc_123"));
        try {
            RootProcessTree.pidFile("!!!");
            fail("unsafe token accepted");
        } catch (IllegalArgumentException expected) {}
    }
}
