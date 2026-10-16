package io.github.mikeceead.xbscompanion;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Room representation of one bounded command run, including its bounded output tails. */
@Entity(tableName = "run_history", indices = {
    @Index(value = {"status"}),
    @Index(value = {"module"}),
    @Index(value = {"finished_at"}),
    @Index(value = {"pinned", "finished_at"})
})
final class HistoryRunEntity {
    @PrimaryKey @NonNull @ColumnInfo(name = "run_id") String runId = "";
    String module = "";
    @ColumnInfo(name = "action_id") String actionId = "";
    String title = "Command";
    String status = "running";
    String command = "";
    String backend = "";
    String workdir = "~/";
    String source = "";
    @ColumnInfo(name = "mutation_class") String mutationClass = "";
    @ColumnInfo(name = "timeout_seconds") int timeoutSeconds;
    String stdout = "";
    String stderr = "";
    String error = "";
    String message = "";
    @ColumnInfo(name = "exit_code") int exitCode = -1;
    @ColumnInfo(name = "started_at") long startedAt;
    @ColumnInfo(name = "finished_at") long finishedAt;
    boolean pinned;

    static HistoryRunEntity fromRecord(RunRecord record) {
        RunRecord safe = record == null ? new RunRecord() : record;
        HistoryRunEntity entity = new HistoryRunEntity();
        entity.runId = RunRecord.limit(safe.runId, RunRecord.MAX_ID_CHARS);
        entity.module = RunRecord.line(safe.module, RunRecord.MAX_SOURCE_CHARS);
        entity.actionId = RunRecord.line(safe.actionId, RunRecord.MAX_SOURCE_CHARS);
        entity.title = RunRecord.line(safe.title, RunRecord.MAX_TITLE_CHARS);
        entity.status = RunRecord.line(safe.status, RunRecord.MAX_SOURCE_CHARS);
        entity.command = RunRecord.limit(safe.command, RunRecord.MAX_COMMAND_CHARS);
        entity.backend = RunRecord.line(safe.backend, RunRecord.MAX_SOURCE_CHARS);
        entity.workdir = RunRecord.line(safe.workdir, RunRecord.MAX_COMMAND_CHARS);
        entity.source = RunRecord.line(safe.source, RunRecord.MAX_SOURCE_CHARS);
        entity.mutationClass = RunRecord.line(safe.mutationClass, RunRecord.MAX_SOURCE_CHARS);
        entity.timeoutSeconds = Math.max(0, Math.min(86_400, safe.timeoutSeconds));
        entity.stdout = RunRecord.tail(safe.stdout, RunRetentionPolicy.MAX_OUTPUT_CHARS);
        entity.stderr = RunRecord.tail(safe.stderr, RunRetentionPolicy.MAX_OUTPUT_CHARS);
        entity.error = RunRecord.tail(safe.error, RunRetentionPolicy.MAX_OUTPUT_CHARS);
        entity.message = RunRecord.line(safe.message, RunRecord.MAX_MESSAGE_CHARS);
        entity.exitCode = safe.exitCode;
        entity.startedAt = Math.max(0L, safe.startedAt);
        entity.finishedAt = Math.max(0L, safe.finishedAt);
        entity.pinned = safe.pinned;
        return entity;
    }

    RunRecord toRecord() {
        RunRecord record = new RunRecord();
        record.runId = RunRecord.validRunId(runId) ? runId.trim() : "";
        record.module = RunRecord.line(module, RunRecord.MAX_SOURCE_CHARS);
        record.actionId = RunRecord.line(actionId, RunRecord.MAX_SOURCE_CHARS);
        record.title = RunRecord.line(title, RunRecord.MAX_TITLE_CHARS);
        record.status = RunRecord.line(status, RunRecord.MAX_SOURCE_CHARS);
        record.command = RunRecord.limit(command, RunRecord.MAX_COMMAND_CHARS);
        record.backend = RunRecord.line(backend, RunRecord.MAX_SOURCE_CHARS);
        record.workdir = RunRecord.line(workdir, RunRecord.MAX_COMMAND_CHARS);
        record.source = RunRecord.line(source, RunRecord.MAX_SOURCE_CHARS);
        record.mutationClass = RunRecord.line(mutationClass, RunRecord.MAX_SOURCE_CHARS);
        record.timeoutSeconds = Math.max(0, Math.min(86_400, timeoutSeconds));
        record.stdout = RunRecord.tail(stdout, RunRetentionPolicy.MAX_OUTPUT_CHARS);
        record.stderr = RunRecord.tail(stderr, RunRetentionPolicy.MAX_OUTPUT_CHARS);
        record.error = RunRecord.tail(error, RunRetentionPolicy.MAX_OUTPUT_CHARS);
        record.message = ResultFormatter.notificationText(RunRecord.line(message, RunRecord.MAX_MESSAGE_CHARS));
        record.exitCode = exitCode;
        record.startedAt = Math.max(0L, startedAt);
        record.finishedAt = Math.max(0L, finishedAt);
        record.pinned = pinned;
        return record;
    }
}
