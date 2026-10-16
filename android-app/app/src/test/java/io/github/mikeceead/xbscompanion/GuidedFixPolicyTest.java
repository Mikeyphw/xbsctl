package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.*;
import org.junit.Test;

public final class GuidedFixPolicyTest {
    private static DebugHealthReport.Item item(String id, String action) {
        return new DebugHealthReport.Item(id, "domain", id, "detail", "source", action,
            DebugHealthReport.BLOCKER, DebugHealthReport.EVIDENCE_CURRENT, 1L);
    }

    @Test public void storageRepairIsExplicitRootMutation() {
        GuidedFixPolicy.Spec spec = GuidedFixPolicy.forItem(item("managed_storage", DebugHealthPolicy.ACTION_CONFIGURATION));
        assertEquals(GuidedFixPolicy.REPAIR_STORAGE, spec.action);
        assertEquals(GuidedFixPolicy.RISK_ROOT_MUTATION, spec.risk);
        assertTrue(spec.confirmationRequired);
        assertTrue(spec.mutatesState());
    }

    @Test public void historyInitializationIsLocalAndExplicit() {
        GuidedFixPolicy.Spec spec = GuidedFixPolicy.forItem(item("history_store", DebugHealthPolicy.ACTION_ACTIVITY));
        assertEquals(GuidedFixPolicy.INITIALIZE_HISTORY, spec.action);
        assertEquals(GuidedFixPolicy.RISK_LOCAL_MUTATION, spec.risk);
        assertTrue(spec.confirmationRequired);
    }

    @Test public void unknownCheckNavigatesWithoutExecution() {
        GuidedFixPolicy.Spec spec = GuidedFixPolicy.forItem(item("future_check", DebugHealthPolicy.ACTION_PROCESS));
        assertEquals(GuidedFixPolicy.OPEN_SOURCE, spec.action);
        assertFalse(spec.executable());
        assertEquals(GuidedFixPolicy.RISK_NAVIGATION, spec.risk);
    }

    @Test public void transactionFixNeverPromisesAutomaticRollback() {
        GuidedFixPolicy.Spec spec = GuidedFixPolicy.forItem(item("config_transaction", DebugHealthPolicy.ACTION_CONFIGURATION));
        assertEquals(GuidedFixPolicy.RECOVER_TRANSACTION, spec.action);
        assertTrue(spec.explanation.contains("never rolled back automatically"));
    }
}
