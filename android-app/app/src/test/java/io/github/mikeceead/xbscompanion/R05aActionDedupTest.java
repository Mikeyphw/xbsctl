package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class R05aActionDedupTest {
    @Test public void boundedWindowOnlyCollapsesIdenticalSignedAction(){
        R05aActionDedup.resetForTest();
        String signature="signed-host:command:argument-digest";
        assertTrue(R05aActionDedup.claim(signature,1_000_000_000L));
        assertFalse(R05aActionDedup.claim(signature,1_000_000_001L));
        assertTrue(R05aActionDedup.claim(signature+"-different",1_000_000_002L));
        assertTrue(R05aActionDedup.claim(signature,2_500_000_001L));
        R05aActionDedup.release(signature);
        assertTrue(R05aActionDedup.claim(signature,2_500_000_002L));
    }
}
