package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class RunRetryPolicyTest {
    @Test public void blocksActiveRuns() {
        RunRecord record = record("running", "state_change", "xbs --json pause");
        RunRetryPolicy.Decision decision = RunRetryPolicy.evaluate(record);
        assertFalse(decision.allowed);
        assertEquals("Active commands cannot be retried", decision.reason);
    }

    @Test public void blocksMissingCommands() {
        RunRetryPolicy.Decision decision = RunRetryPolicy.evaluate(record("ok", "read_only", "  "));
        assertFalse(decision.allowed);
        assertEquals("Run has no stored command", decision.reason);
    }

    @Test public void nonDestructiveRunsCanLaunchImmediately() {
        RunRecord record = record("ok", "state_change", "xbs --json reload");
        record.actionId = "reload";
        RunRetryPolicy.Decision decision = RunRetryPolicy.evaluate(record);
        assertTrue(decision.allowed);
        assertFalse(decision.requiresConfirmation);
        assertFalse(decision.destructive);
        assertEquals("retry_reload", decision.actionId);
        assertEquals("xbs --json reload", decision.command);
    }

    @Test public void destructiveRunsRequireConfirmation() {
        RunRecord record = record("failed", "destructive", "XBSctl --json remove 'old_event'");
        record.title = "Remove event";
        record.actionId = "event-remove";
        RunRetryPolicy.Decision decision = RunRetryPolicy.evaluate(record);
        assertTrue(decision.allowed);
        assertTrue(decision.requiresConfirmation);
        assertTrue(decision.destructive);
        assertEquals("retry_event_remove", decision.actionId);
        assertTrue(decision.message.contains("destructive root command"));
        assertTrue(decision.message.contains("XBSctl --json remove"));
    }

    @Test public void retryPrefixIsNotRepeated() {
        RunRecord record = record("ok", "read_only", "xbs --json status");
        record.actionId = "retry_status";
        assertEquals("retry_status", RunRetryPolicy.retryActionId(record));
    }

    private static RunRecord record(String status, String mutation, String command) {
        RunRecord record = new RunRecord();
        record.status = status;
        record.mutationClass = mutation;
        record.command = command;
        record.title = "Saved command";
        return record;
    }
}
