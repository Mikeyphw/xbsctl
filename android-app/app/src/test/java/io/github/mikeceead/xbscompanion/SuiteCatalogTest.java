package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class SuiteCatalogTest {
    @Test public void exposesOnlyXbsActions() {
        assertFalse(SuiteCatalog.all().isEmpty());
        for (SuiteAction action : SuiteCatalog.all()) assertEquals("xbs", action.module);
    }

    @Test public void eventArgumentsAreQuotedAndStrictlyBounded() {
        SuiteAction action = SuiteCatalog.find("xbs", "event_run");
        assertNotNull(action);
        assertEquals("xbs --json event run 'screen_off'", action.command("screen_off"));
        assertEquals("xbs --json event run 'custom:event-01'", action.command("custom:event-01"));
        try {
            action.command("a'b");
            fail("Expected unsupported character rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unsupported"));
        }
        try {
            action.command("manual\nstatus");
            fail("Expected multiline argument rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("one shell token line"));
        }
    }

    @Test public void allActionsUseRoot() {
        for (SuiteAction action : SuiteCatalog.all()) assertEquals("root", action.defaultBackend);
    }

    @Test public void notificationActionsExist() {
        assertNotNull(SuiteCatalog.find("xbs", "pause"));
        assertNotNull(SuiteCatalog.find("xbs", "resume"));
        assertNotNull(SuiteCatalog.find("xbs", "safe_on"));
        assertNotNull(SuiteCatalog.find("xbs", "safe_off"));
        assertNotNull(SuiteCatalog.find("xbs", "event_stop"));
    }
}
