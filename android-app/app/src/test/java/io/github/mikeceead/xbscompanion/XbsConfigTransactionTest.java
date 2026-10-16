package io.github.mikeceead.xbscompanion;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public final class XbsConfigTransactionTest {
    @Test public void managedPathPolicyAllowsOnlyDirectListChildren() {
        assertTrue(XbsConfigTransaction.isManagedListPath(XbsConfigApi.LIST_DIR + "/gaming.allowlist"));
        assertTrue(XbsConfigTransaction.isManagedListPath(XbsConfigApi.LIST_DIR + "/essentials.allowlist"));
        assertFalse(XbsConfigTransaction.isManagedListPath(XbsConfigApi.CONFIG));
        assertFalse(XbsConfigTransaction.isManagedListPath(XbsConfigApi.LIST_DIR + "/nested/file"));
        assertFalse(XbsConfigTransaction.isManagedListPath(XbsConfigApi.LIST_DIR + "/../XtremeBS.json"));
        assertFalse(XbsConfigTransaction.isManagedListPath("/data/local/tmp/foreign"));
    }

    @Test public void planRejectsWriteDeleteConflictAndExternalTargets() {
        Map<String,String> writes = new LinkedHashMap<>();
        String managed = XbsConfigApi.LIST_DIR + "/gaming.allowlist";
        writes.put(managed, "com.example.game\n");
        Set<String> deletes = new LinkedHashSet<>();
        deletes.add(managed);
        try {
            XbsConfigTransaction.create(new JSONObject(), writes, deletes, true);
            fail("Expected write/delete conflict");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("written and deleted"));
        }

        writes.clear();
        writes.put("/data/local/tmp/external", "x\n");
        try {
            XbsConfigTransaction.create(new JSONObject(), writes, Collections.emptySet(), false);
            fail("Expected external path rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("outside the managed"));
        }
    }

    @Test public void executeShellSerializesStagesDigestsValidatesAndRollsBack() {
        XbsConfigTransaction.Plan plan = new XbsConfigTransaction.Plan(
            "test-transaction", 1234L, true,
            Arrays.asList(
                XbsConfigTransaction.Entry.write(XbsConfigApi.CONFIG, "{}"),
                XbsConfigTransaction.Entry.write(XbsConfigApi.LIST_DIR + "/gaming.allowlist", "com.example.game\n"),
                XbsConfigTransaction.Entry.delete(XbsConfigApi.LIST_DIR + "/old.proc")
            )
        );
        String shell = XbsConfigTransaction.buildExecuteShellForTest(plan);
        assertTrue(shell.contains("acquire_lock"));
        assertTrue(shell.contains("Another configuration transaction is active"));
        assertTrue(shell.contains("reclaim stale configuration transaction lock"));
        assertTrue(shell.contains("write_status preparing"));
        assertTrue(shell.contains("write_status prepared"));
        assertTrue(shell.contains("write_status promoting"));
        assertTrue(shell.contains("write_status promoted"));
        assertTrue(shell.contains("write_status committed"));
        assertTrue(shell.contains("Automatic rollback restored the complete old file set"));
        assertTrue(shell.contains("Automatic rollback could not verify"));
        assertTrue(shell.contains("hash_file"));
        assertTrue(shell.contains("manifest.tsv"));
        assertTrue(shell.contains("snapshots.tsv"));
        assertTrue(shell.contains("$SNAPSHOTS.complete"));
        assertTrue(shell.contains("--json validate"));
        assertTrue(shell.contains("/data/adb/modules/XtremeBS/system/bin/XBSctl"));
        assertTrue(shell.contains("/data/adb/modules_update/XtremeBS/system/bin/XBSctl"));
        assertTrue(shell.contains("/data/adb/ksu/modules/XtremeBS/system/bin/XBSctl"));
        assertTrue(shell.contains("/data/adb/ksu/modules_update/XtremeBS/system/bin/XBSctl"));
        assertTrue(shell.contains("\"$XBSCTL_BIN\" --json reload"));
        assertFalse(shell.contains("XBSctl --json reload"));
        assertTrue(shell.contains("cmp -s"));
        assertTrue(shell.contains("Refusing symbolic-link target"));
        assertFalse(shell.contains("chmod 777"));
        assertFalse(shell.contains("rm -rf /"));
    }

    @Test public void parsedJournalRequiresPlanSnapshotsAndExactEntryCount() {
        String configPath = java.util.Base64.getEncoder().encodeToString(XbsConfigApi.CONFIG.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String digest = repeat('a', 64);
        String raw = "id=tx\ncreated_at=1\nupdated_at=2\nstatus=committed\nreload=true\nentry_count=1\nsnapshots_complete=true\nmessage=done\n"
            + "__XBS_PLAN__\nW\t" + configPath + "\t" + digest + "\n"
            + "__XBS_SNAPSHOTS__\n" + configPath + "\tabsent\t-\n";
        XbsConfigTransaction.Record record = XbsConfigTransaction.parseRecordForTest(raw);
        assertTrue(record.canRollback());
        assertEquals("", record.error);

        XbsConfigTransaction.Record bad = XbsConfigTransaction.parseRecordForTest(raw.replace("entry_count=1", "entry_count=2"));
        assertFalse(bad.canRollback());
        assertTrue(bad.error.contains("entry count"));
    }

    @Test public void preparingRecordCanRecoverBeforeEverySnapshotExists() {
        String configPath = java.util.Base64.getEncoder().encodeToString(XbsConfigApi.CONFIG.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String listPath = java.util.Base64.getEncoder().encodeToString((XbsConfigApi.LIST_DIR + "/gaming.allowlist").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String digest = repeat('a', 64);
        String raw = "id=tx\ncreated_at=1\nupdated_at=2\nstatus=preparing\nreload=true\nentry_count=2\nsnapshots_complete=false\nmessage=staging\n"
            + "__XBS_PLAN__\nW\t" + configPath + "\t" + digest + "\nW\t" + listPath + "\t" + digest + "\n"
            + "__XBS_SNAPSHOTS__\n" + configPath + "\tpresent\t" + repeat('b', 64) + "\n";
        XbsConfigTransaction.Record record = XbsConfigTransaction.parseRecordForTest(raw);
        assertTrue(record.needsRecovery());
        assertFalse(record.canRollback());
        assertEquals("pending", record.entries.get(1).oldState);
        assertTrue(XbsConfigTransaction.buildRecoveryShellForTest(record).contains("ALLOW_PENDING=true"));
        XbsConfigTransaction.Record killedRecovery = XbsConfigTransaction.parseRecordForTest(
            raw.replace("status=preparing", "status=recovering"));
        assertTrue(killedRecovery.needsRecovery());
    }

    @Test public void rollbackGuardsLatestPointerAndPostCommitChanges() {
        XbsConfigTransaction.Entry config = XbsConfigTransaction.Entry.journal(
            XbsConfigApi.CONFIG, false, "present", repeat('b', 64), repeat('c', 64));
        XbsConfigTransaction.Record committed = new XbsConfigTransaction.Record(
            "tx", "committed", 1L, 2L, true, true, 1, "done", Collections.singletonList(config), ""
        );
        assertTrue(committed.canRollback());
        String shell = XbsConfigTransaction.buildRollbackShellForTest(committed);
        assertTrue(shell.contains("guard_latest"));
        assertTrue(shell.contains("no longer the latest"));
        assertTrue(shell.contains("Managed files changed after commit; refusing stale rollback"));
        assertTrue(shell.contains("verify_committed"));
        assertTrue(shell.contains("reapply_committed"));
        assertTrue(shell.contains("rollback_failed"));
    }

    @Test public void failedRollbackAndRecoveryRemainRecoverable() {
        XbsConfigTransaction.Entry config = XbsConfigTransaction.Entry.journal(
            XbsConfigApi.CONFIG, false, "present", repeat('b', 64), repeat('c', 64));
        for (String state : new String[]{"preparing", "prepared", "promoting", "promoted", "rolling_back", "recovering", "rollback_failed", "recovery_failed"}) {
            XbsConfigTransaction.Record record = new XbsConfigTransaction.Record(
                "tx", state, 1L, 2L, true, true, 1, "busy", Collections.singletonList(config), ""
            );
            assertTrue(state, record.needsRecovery());
        }
        XbsConfigTransaction.Record interrupted = new XbsConfigTransaction.Record(
            "tx", "promoting", 1L, 2L, true, true, 1, "busy", Collections.singletonList(config), ""
        );
        String recovery = XbsConfigTransaction.buildRecoveryShellForTest(interrupted);
        assertTrue(recovery.contains("write_status recovering"));
        assertTrue(recovery.contains("write_status recovered"));
        assertTrue(recovery.contains("write_status recovery_failed"));
        assertTrue(recovery.contains("retry is safe"));
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
