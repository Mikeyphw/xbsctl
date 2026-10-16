package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class XbsCommandPolicyTest {
    @Test public void readOnlyActionsUseShortReadTimeout() {
        XbsCommandSpec spec = XbsCommandSpec.fromAction(SuiteCatalog.find("xbs", "status"), "", ExecutionSource.APP);
        assertEquals(MutationClass.READ_ONLY, spec.mutationClass);
        assertEquals(TimeoutPolicy.READ_ONLY_SECONDS, spec.timeout.seconds);
        assertEquals("app", spec.source.label);
    }

    @Test public void runtimeTogglesAreStateChanges() {
        XbsCommandSpec spec = XbsCommandSpec.fromAction(SuiteCatalog.find("xbs", "pause"), "", ExecutionSource.NOTIFICATION);
        assertEquals(MutationClass.STATE_CHANGE, spec.mutationClass);
        assertEquals(TimeoutPolicy.STATE_CHANGE_SECONDS, spec.timeout.seconds);
        assertTrue(spec.startMessage().contains("notification"));
    }

    @Test public void destructiveActionsGetLongerDeadline() {
        XbsCommandSpec spec = XbsCommandSpec.fromAction(SuiteCatalog.find("xbs", "event_stop"), "manual", ExecutionSource.APP);
        assertEquals(MutationClass.DESTRUCTIVE, spec.mutationClass);
        assertEquals(TimeoutPolicy.DESTRUCTIVE_SECONDS, spec.timeout.seconds);
        assertEquals("xbs --json event stop 'manual'", spec.command);
    }

    @Test public void policyRejectsNonXbsActions() {
        SuiteAction action = new SuiteAction("other", "id", "Title", "Description", "echo bad", "root", false, false);
        try {
            XbsCommandSpec.fromAction(action, "", ExecutionSource.APP);
            fail("Expected non-XBS action rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("Only XBS actions are allowed", expected.getMessage());
        }
    }

    @Test public void policyRejectsForgedSuiteCatalogActionObjects() {
        SuiteAction forged = new SuiteAction("xbs", "pause", "Pause", "Forged pause", "xbs --json event stop 'manual'", "root", false, true);
        try {
            XbsCommandSpec.fromAction(forged, "", ExecutionSource.APP);
            fail("Expected forged catalog action rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("Suite catalog action instance does not match the allowlist", expected.getMessage());
        }
    }

    @Test public void timeoutClampKeepsReasonableBounds() {
        assertEquals(TimeoutPolicy.MIN_SECONDS, TimeoutPolicy.clamp(1));
        assertEquals(TimeoutPolicy.MAX_SECONDS, TimeoutPolicy.clamp(999));
        assertEquals(42, TimeoutPolicy.clamp(42));
    }
}
