package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class XbsCliCatalogTest {
    @Test public void exposesCompleteLiteralSurface() {
        assertEquals(41, XbsCliCatalog.all().size());
        assertEquals(39, XbsCliCatalog.runnable().size());
        assertNotNull(XbsCliCatalog.find("schema_root"));
        assertNotNull(XbsCliCatalog.find("completion_complete_zsh"));
        assertNotNull(XbsCliCatalog.find("config_edit_cli"));
        assertNotNull(XbsCliCatalog.find("ctl_reload"));
        assertNotNull(XbsCliCatalog.find("ctl_pause"));
        assertNotNull(XbsCliCatalog.find("ctl_resume"));
        assertNotNull(XbsCliCatalog.find("ctl_safe"));
    }

    @Test public void globalFlagsPrecedeXbsPath() {
        CliCommand command = XbsCliCatalog.find("status");
        Map<String,String> values = CliCommand.defaults(command);
        values.put(CliCommand.GLOBAL_QUIET, "true");
        values.put(CliCommand.GLOBAL_NO_INTERACTIVE, "true");
        assertEquals("xbs --json --quiet --no-interactive status", command.build(values));
    }

    @Test public void controllerWaitAndTimeoutAreTyped() {
        CliCommand command = XbsCliCatalog.find("ctl_start");
        Map<String,String> values = CliCommand.defaults(command);
        values.put("event", "gaming");
        values.put("wait", "true");
        values.put("timeout", "12");
        assertEquals("XBSctl --json start 'gaming' --wait --timeout '12'", command.build(values));
    }

    @Test public void controllerBlankEventUsesCliDefault() {
        CliCommand command = XbsCliCatalog.find("ctl_stop");
        assertEquals("XBSctl --json stop", command.build(CliCommand.defaults(command)));
    }

    @Test public void hiddenCompletionWordsRemainSeparateArguments() {
        CliCommand command = XbsCliCatalog.find("completion_complete_zsh");
        Map<String,String> values = CliCommand.defaults(command);
        values.put("words", "xbs runtime safe");
        values.put("index", "2");
        assertEquals("xbs --json completion complete-zsh --words 'xbs' 'runtime' 'safe' --index '2'", command.build(values));
    }
}
