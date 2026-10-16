package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class PersistedCommandRecoveryTest {
    @Test public void suiteActionRoundTripsThroughCurrentAllowlist() {
        SuiteAction action = SuiteCatalog.find("xbs", "event_run");
        XbsCommandSpec original = XbsCommandSpec.fromAction(action, "manual", ExecutionSource.APP);
        XbsCommandSpec recovered = XbsCommandSpec.recoverPersisted(action.id, original.command,
            ExecutionSource.APP, original.recoveryKind, original.recoveryData);
        assertEquals(original.command, recovered.command);
        assertEquals("suite", recovered.recoveryKind);
    }

    @Test public void catalogCommandRoundTripsWithValidatedFields() {
        CliCommand command = XbsCliCatalog.find("event_run");
        Map<String,String> values = new LinkedHashMap<>();
        values.put("event", "manual");
        XbsCommandSpec original = XbsCommandSpec.fromCatalog(command, values, ExecutionSource.APP);
        XbsCommandSpec recovered = XbsCommandSpec.recoverPersisted(command.id, original.command,
            ExecutionSource.APP, original.recoveryKind, original.recoveryData);
        assertEquals(original.command, recovered.command);
        assertEquals("catalog", recovered.recoveryKind);
    }

    @Test public void tamperedPersistedCommandFailsClosed() {
        SuiteAction action = SuiteCatalog.find("xbs", "event_run");
        XbsCommandSpec original = XbsCommandSpec.fromAction(action, "manual", ExecutionSource.APP);
        try {
            XbsCommandSpec.recoverPersisted(action.id, original.command + " ; id", ExecutionSource.APP,
                original.recoveryKind, original.recoveryData);
            fail("tampered command recovered");
        } catch (IllegalArgumentException expected) {}
    }

    @Test public void customRawCommandsAreNotReexecutedAfterProcessDeath() {
        SuiteAction custom = SuiteAction.custom("custom-test", "Custom", "", "xbs --json status", false);
        XbsCommandSpec spec = XbsCommandSpec.fromAction(custom, "", ExecutionSource.APP);
        assertEquals("none", spec.recoveryKind);
        try {
            XbsCommandSpec.recoverPersisted(custom.id, spec.command, ExecutionSource.APP,
                spec.recoveryKind, spec.recoveryData);
            fail("custom command should not be recoverable");
        } catch (IllegalArgumentException expected) {}
    }
}
