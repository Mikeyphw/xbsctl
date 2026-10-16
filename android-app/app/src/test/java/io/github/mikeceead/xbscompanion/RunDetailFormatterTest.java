package io.github.mikeceead.xbscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class RunDetailFormatterTest {
    @Test public void activeStatusesDriveCancellationControls() {
        RunRecord running = new RunRecord();
        running.status = "running";
        RunRecord cancelling = new RunRecord();
        cancelling.status = "cancelling";
        RunRecord done = new RunRecord();
        done.status = "ok";
        assertTrue(RunDetailFormatter.isActive(running));
        assertTrue(RunDetailFormatter.isActive(cancelling));
        assertFalse(RunDetailFormatter.isActive(done));
    }

    @Test public void statusLineShowsSourceMutationAndTimeout() {
        RunRecord record = new RunRecord();
        record.status = "running";
        record.source = "notification";
        record.mutationClass = "state-change";
        record.timeoutSeconds = 45;
        assertEquals("RUNNING · notification · state-change · timeout 45s", RunDetailFormatter.statusLine(record));
    }

    @Test public void completedStatusLineIncludesExitCode() {
        RunRecord record = new RunRecord();
        record.status = "failed";
        record.exitCode = 124;
        record.source = "app";
        record.mutationClass = "read-only";
        assertEquals("FAILED · exit 124 · app · read-only", RunDetailFormatter.statusLine(record));
    }

    @Test public void detailBodyKeepsStdoutAndStderrSeparate() {
        RunRecord record = new RunRecord();
        record.runId = "root-1";
        record.title = "Status";
        record.status = "ok";
        record.command = "xbs --json status";
        record.stdout = "out";
        record.stderr = "err";
        String body = RunDetailFormatter.detailBody(record);
        assertTrue(body.contains("STDOUT\nout"));
        assertTrue(body.contains("STDERR\nerr"));
    }
}
