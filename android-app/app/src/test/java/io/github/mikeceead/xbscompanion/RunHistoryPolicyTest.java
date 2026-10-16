package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class RunHistoryPolicyTest {
    @Test public void filtersSeparateTerminalStates() {
        RunRecord ok = record("ok", 0);
        RunRecord failed = record("failed", 1);
        RunRecord timedOut = record("failed", 124);
        RunRecord cancelled = record("failed", 130);
        assertTrue(RunFilter.SUCCEEDED.matches(ok));
        assertTrue(RunFilter.FAILED.matches(failed));
        assertFalse(RunFilter.FAILED.matches(timedOut));
        assertFalse(RunFilter.FAILED.matches(cancelled));
        assertTrue(RunFilter.TIMED_OUT.matches(timedOut));
        assertTrue(RunFilter.CANCELLED.matches(cancelled));
    }

    @Test public void activeAndRetryableAreMutuallyExclusive() {
        RunRecord running = record("running", -1);
        running.command = "xbs --json status";
        RunRecord done = record("ok", 0);
        done.command = "xbs --json status";
        assertTrue(RunFilter.ACTIVE.matches(running));
        assertFalse(RunFilter.isRetryable(running));
        assertTrue(RunFilter.isRetryable(done));
    }

    @Test public void retentionKeepsNewestRecordsWithinLimit() {
        List<RunRecord> records = new ArrayList<>();
        for (int i = 0; i < RunRetentionPolicy.MAX_RECORDS + 7; i++) {
            RunRecord record = record("ok", 0);
            record.runId = "root-" + i;
            // finishedAt=0 is the persisted sentinel for an unfinished run; use
            // positive finish times so this test exercises newest-finished
            // retention rather than the active/fallback sort path.
            record.finishedAt = i + 1;
            records.add(record);
        }
        List<RunRecord> trimmed = RunRetentionPolicy.trim(records);
        assertEquals(RunRetentionPolicy.MAX_RECORDS, trimmed.size());
        assertEquals("root-" + (RunRetentionPolicy.MAX_RECORDS + 6), trimmed.get(0).runId);
    }

    @Test public void retentionKeepsActiveRunsBeforeOlderFinishedRuns() {
        List<RunRecord> records = new ArrayList<>();
        for (int i = 0; i < RunRetentionPolicy.MAX_RECORDS + 2; i++) {
            RunRecord record = record("ok", 0);
            record.runId = "done-" + i;
            record.finishedAt = 1000 + i;
            records.add(record);
        }
        RunRecord active = record("running", -1);
        active.runId = "active";
        active.startedAt = 1;
        records.add(active);
        List<RunRecord> trimmed = RunRetentionPolicy.trim(records);
        boolean found = false;
        for (RunRecord record : trimmed) if ("active".equals(record.runId)) found = true;
        assertTrue(found);
    }



    @Test public void runRecordJsonAndEntitiesBoundLocalHistoryEvidence() throws Exception {
        RunRecord record = record("failed", 1);
        record.runId = "run-history-bound";
        record.title = "t".repeat(500);
        record.stdout = "s".repeat(RunRetentionPolicy.MAX_OUTPUT_CHARS + 100);
        String json = record.toJson().toString();
        RunRecord restored = RunRecord.fromJson(new org.json.JSONObject(json));
        assertEquals(RunRecord.MAX_TITLE_CHARS, restored.title.length());
        assertEquals(RunRetentionPolicy.MAX_OUTPUT_CHARS, restored.stdout.length());
        HistoryRunEntity entity = HistoryRunEntity.fromRecord(record);
        assertEquals(RunRecord.MAX_TITLE_CHARS, entity.title.length());
        assertEquals(RunRetentionPolicy.MAX_OUTPUT_CHARS, entity.stdout.length());
    }

    @Test public void roomEntityReadbackRejectsInvalidIdsAndBoundsFields() {
        HistoryRunEntity entity = new HistoryRunEntity();
        entity.runId = "bad id with spaces";
        entity.title = "t".repeat(1000);
        entity.stdout = "s".repeat(RunRetentionPolicy.MAX_OUTPUT_CHARS + 50);
        RunRecord rejected = entity.toRecord();
        assertEquals("", rejected.runId);
        assertEquals(RunRecord.MAX_TITLE_CHARS, rejected.title.length());
        assertEquals(RunRetentionPolicy.MAX_OUTPUT_CHARS, rejected.stdout.length());
    }

    @Test public void oversizedTransactionRoomEvidenceFailsClosed() {
        String huge = "status=committed\n" + "x".repeat(HistoryTransactionEntity.MAX_SUPPORT_TEXT_CHARS + 1);
        assertEquals(HistoryTransactionEntity.OVERSIZED_SUPPORT_TEXT, HistoryTransactionEntity.boundedSupportText(huge));
    }

    @Test public void transactionEntityBoundsSupportEvidence() {
        XbsConfigTransaction.Record record = new XbsConfigTransaction.Record(
            "tx-history-bound", "committed", 1L, 2L, true, true, 1,
            "m".repeat(5000), java.util.Collections.emptyList(), "e".repeat(5000));
        HistoryTransactionEntity entity = HistoryTransactionEntity.fromRecord(record);
        assertTrue(entity.message.length() <= HistoryTransactionEntity.MAX_MESSAGE_CHARS);
        assertTrue(entity.error.length() <= HistoryTransactionEntity.MAX_MESSAGE_CHARS);
        assertTrue(entity.supportText.length() <= HistoryTransactionEntity.MAX_SUPPORT_TEXT_CHARS);
    }

    private static RunRecord record(String status, int exitCode) {
        RunRecord record = new RunRecord();
        record.status = status;
        record.exitCode = exitCode;
        record.finishedAt = exitCode >= 0 ? 1 : 0;
        return record;
    }
}
