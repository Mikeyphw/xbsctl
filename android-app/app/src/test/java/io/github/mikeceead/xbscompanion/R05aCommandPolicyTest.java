package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public final class R05aCommandPolicyTest {
    @Test public void waitsForAllSupportedControllerMutations(){
        for(String s:new String[]{"XBSctl --json start 'manual'","XBSctl stop 'manual'","XBSctl reload","XBSctl pause","XBSctl resume","XBSctl safe","xbs --json event run 'manual'","xbs event stop 'charging'","xbs runtime pause","xbs runtime safe off","xbs runtime reload"}){
            assertTrue(s,R05aCommandPolicy.requiresAck(s));
            String protectedCommand=R05aCommandPolicy.forceWait(s);
            assertTrue(protectedCommand,protectedCommand.contains("--json"));
            assertTrue(protectedCommand,protectedCommand.contains("--wait"));
            assertTrue(protectedCommand,protectedCommand.contains("--timeout 12"));
        }
    }
    @Test public void readsCannotTriggerWait(){
        for(String s:new String[]{"xbs status","xbs config get header.delay","XBSctl status","xbs event list","echo 'XBSctl reload'","XBSctl; echo reload","xbs runtime safe && echo ok"}){
            assertFalse(s,R05aCommandPolicy.requiresAck(s));assertEquals(s,R05aCommandPolicy.forceWait(s));
        }
    }
    @Test public void avoidsDuplicateFlags(){
        String s="XBSctl --json start 'manual' --wait --timeout 17";
        assertEquals(s,R05aCommandPolicy.forceWait(s));
        assertEquals("resume",R05aCommandPolicy.expectedAction("xbs runtime safe off"));
    }
}
