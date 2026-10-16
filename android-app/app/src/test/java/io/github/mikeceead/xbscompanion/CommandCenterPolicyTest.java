package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

public final class CommandCenterPolicyTest {
    @Test public void catalogIsCompleteAndEveryEntryHasPolicyMetadata() {
        assertTrue(XbsCliCatalog.all().size() >= 40);
        for (CliCommand command : XbsCliCatalog.all()) {
            CommandCenterPolicy.Descriptor descriptor = CommandCenterPolicy.describe(
                command, RootDiagnosticsReport.unchecked(), 1L);
            assertFalse(command.id, descriptor.expectedEffect.isEmpty());
            assertFalse(command.id, descriptor.riskLabel().isEmpty());
            if (!command.isNative()) assertTrue(command.id, descriptor.timeoutSeconds >= TimeoutPolicy.MIN_SECONDS);
        }
    }

    @Test public void androidUnsafeCommandsStayVisibleButBlocked() {
        for (String id : new String[]{"config_set", "config_edit_cli", "completion_install", "completion_complete_zsh", "manpage_install"}) {
            CliCommand command = XbsCliCatalog.find(id);
            assertTrue(id, command != null);
            CommandCenterPolicy.Descriptor descriptor = CommandCenterPolicy.describe(
                command, RootDiagnosticsReport.unchecked(), 1L);
            assertEquals(id, CommandCenterPolicy.Availability.BLOCKED, descriptor.availability);
            assertFalse(id, descriptor.guidance.isEmpty());
        }
    }

    @Test public void destructiveActionsRequireExplicitConfirmation() {
        CommandCenterPolicy.Descriptor descriptor = CommandCenterPolicy.describe(
            XbsCliCatalog.find("event_stop"), RootDiagnosticsReport.unchecked(), 1L);
        assertEquals(CommandCenterPolicy.Risk.DESTRUCTIVE, descriptor.risk);
        assertTrue(descriptor.confirmationRequired);
        assertEquals(TimeoutPolicy.DESTRUCTIVE_SECONDS, descriptor.timeoutSeconds);
    }

    @Test public void catalogArgumentsRemainQuotedAndTyped() {
        CliCommand command = XbsCliCatalog.find("event_run");
        Map<String,String> values = CliCommand.defaults(command);
        values.put("event", "custom:event-01");
        String generated = command.build(values);
        assertTrue(generated.contains("'custom:event-01'"));
        XbsCommandSpec spec = XbsCommandSpec.fromCatalog(command, values, ExecutionSource.APP);
        assertEquals(generated, spec.command);
        assertEquals(MutationClass.STATE_CHANGE, spec.mutationClass);

        values.put("event", "charging; echo unsafe");
        try {
            command.build(values);
            throw new AssertionError("unsafe event argument must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unsupported event-name characters"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void blockedCatalogCommandCannotBecomeTypedSpec() {
        CliCommand command = XbsCliCatalog.find("config_set");
        XbsCommandSpec.fromCatalog(command, CliCommand.defaults(command), ExecutionSource.APP);
    }

    @Test(expected = IllegalArgumentException.class)
    public void forgedCatalogObjectCannotBecomeTypedSpec() {
        CliCommand forged = new CliCommand("status", "General", "Runtime status", "Forged",
            "xbs", new String[]{"status"}, false, "");
        XbsCommandSpec.fromCatalog(forged, Collections.emptyMap(), ExecutionSource.APP);
    }

    @Test(expected = IllegalArgumentException.class)
    public void commandFieldTimeoutIsBounded() {
        CliCommand command = XbsCliCatalog.find("ctl_reload");
        command.build(Collections.singletonMap("timeout", "9999"));
    }
}
