package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class ActivityQueryTest {
    private static final long NOW = 10_000_000L;

    @Test public void searchCoversCommandAndOutputEvidence() {
        RunRecord record = run("ok", 0, "tasker", NOW - 1000L);
        record.title = "Reload configuration";
        record.stderr = "warning from validator";
        assertTrue(new ActivityQuery("validator", ActivityQuery.Status.ALL,
            ActivityQuery.Window.ALL, ActivityQuery.SOURCE_ALL, false).matches(record, NOW));
        assertTrue(new ActivityQuery("reload", ActivityQuery.Status.ALL,
            ActivityQuery.Window.ALL, ActivityQuery.SOURCE_ALL, false).matches(record, NOW));
    }

    @Test public void problemClassificationIncludesTimeoutCancellationAndErrorEvidence() {
        assertTrue(ActivityQuery.isProblem(run("failed", 124, "app", NOW)));
        assertTrue(ActivityQuery.isProblem(run("failed", 130, "app", NOW)));
        RunRecord stderr = run("ok", 0, "app", NOW);
        stderr.stderr = "warning";
        assertTrue(ActivityQuery.isProblem(stderr));
    }

    @Test public void sourceWindowAndPinnedFiltersCompose() {
        RunRecord wanted = run("ok", 0, "tasker", NOW - 1000L);
        wanted.pinned = true;
        RunRecord old = run("ok", 0, "tasker", NOW - ActivityQuery.Window.DAY.ageMs - 1L);
        old.pinned = true;
        RunRecord app = run("ok", 0, "app", NOW - 1000L);
        app.pinned = true;
        ActivityQuery query = new ActivityQuery("", ActivityQuery.Status.SUCCEEDED,
            ActivityQuery.Window.DAY, "tasker", true);
        List<RunRecord> result = query.apply(Arrays.asList(old, app, wanted), NOW);
        assertEquals(1, result.size());
        assertSame(wanted, result.get(0));
    }

    @Test public void eventProblemFilterUsesImportantEvidence() {
        XbsEvent warning = new XbsEvent("1", "safe_mode", "runtime", "active", NOW / 1000L,
            "warning", "Safe mode enabled", false, "{}");
        XbsEvent info = new XbsEvent("2", "profile", "charging", "active", NOW / 1000L,
            "info", "Charging started", false, "{}");
        ActivityQuery query = new ActivityQuery("", ActivityQuery.Status.PROBLEMS,
            ActivityQuery.Window.ALL, ActivityQuery.SOURCE_ALL, false);
        assertTrue(query.matches(warning, NOW));
        assertFalse(query.matches(info, NOW));
    }

    @Test public void pinnedRetentionSurvivesOlderThanUnpinnedRecords() {
        RunRecord pinned = run("ok", 0, "app", 1L);
        pinned.runId = "pinned";
        pinned.pinned = true;
        List<RunRecord> input = new java.util.ArrayList<>();
        input.add(pinned);
        for (int index = 0; index < 30; index++) {
            RunRecord record = run("ok", 0, "app", 100L + index);
            record.runId = "new-" + index;
            input.add(record);
        }
        List<RunRecord> result = RunRetentionPolicy.trim(input, 30);
        assertEquals(30, result.size());
        assertTrue(result.stream().anyMatch(item -> "pinned".equals(item.runId)));
        assertTrue(result.stream().anyMatch(item -> "new-29".equals(item.runId)));
    }

    @Test public void selectedExportIsBoundedAndIncludesPinEvidence() {
        RunRecord record = run("failed", 1, "notification", NOW);
        record.runId = "run-1";
        record.title = "Validate";
        record.pinned = true;
        record.stdout = "x".repeat(8000);
        String export = ActivityExportFormatter.format(Arrays.asList(record));
        assertTrue(export.contains("records=1"));
        assertTrue(export.contains("pinned=true"));
        assertTrue(export.length() < 7000);
    }



    @Test public void problemClassificationAcceptsStatusOnlyTimeoutAndCancellation() {
        assertTrue(ActivityQuery.isProblem(run("timeout", -1, "app", NOW)));
        assertTrue(ActivityQuery.isProblem(run("cancelled", -1, "app", NOW)));
        assertTrue(new ActivityQuery("", ActivityQuery.Status.TIMED_OUT,
            ActivityQuery.Window.ALL, ActivityQuery.SOURCE_ALL, false).matches(run("timed_out", -1, "app", NOW), NOW));
        assertTrue(new ActivityQuery("", ActivityQuery.Status.CANCELLED,
            ActivityQuery.Window.ALL, ActivityQuery.SOURCE_ALL, false).matches(run("canceled", -1, "app", NOW), NOW));
    }

    @Test public void queryAndExportsAreBounded() {
        String huge = "x".repeat(ActivityQuery.MAX_QUERY_CHARS + 1000);
        ActivityQuery query = new ActivityQuery(huge, ActivityQuery.Status.ALL,
            ActivityQuery.Window.ALL, "unknown-source;rm -rf", false);
        assertEquals(ActivityQuery.MAX_QUERY_CHARS, query.text.length());
        assertEquals(ActivityQuery.SOURCE_ALL, query.source);
        java.util.List<RunRecord> records = new java.util.ArrayList<>();
        for (int index = 0; index < 100; index++) {
            RunRecord record = run("failed", 1, "app", NOW + index);
            record.runId = "export-" + index;
            record.stdout = "o".repeat(9000);
            records.add(record);
        }
        String export = ActivityExportFormatter.format(records);
        assertTrue(export.contains("exported=60"));
        assertTrue(export.contains("omitted=40"));
        assertTrue(export.length() <= 192_000);
    }

    private static RunRecord run(String status, int exit, String source, long finishedAt) {
        RunRecord record = new RunRecord();
        record.runId = "run-" + finishedAt + "-" + source;
        record.status = status;
        record.exitCode = exit;
        record.source = source;
        record.startedAt = Math.max(1L, finishedAt - 10L);
        record.finishedAt = finishedAt;
        return record;
    }
}
