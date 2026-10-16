package io.github.mikeceead.xbscompanion;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Bounded local evidence for configuration transactions; root-side snapshots remain authoritative. */
@Entity(tableName = "config_transaction_history", indices = {@Index(value = {"updated_at"})})
final class HistoryTransactionEntity {
    static final int MAX_SUPPORT_TEXT_CHARS = 4096;
    static final int MAX_MESSAGE_CHARS = 512;
    static final String OVERSIZED_SUPPORT_TEXT =
        "status=invalid_oversized\nmessage=Transaction evidence exceeded support bounds\n";
    @PrimaryKey @NonNull String id = "";
    String status = "unknown";
    @ColumnInfo(name = "created_at") long createdAt;
    @ColumnInfo(name = "updated_at") long updatedAt;
    boolean reload;
    @ColumnInfo(name = "snapshots_complete") boolean snapshotsComplete;
    @ColumnInfo(name = "entry_count") int entryCount;
    String message = "";
    String error = "";
    @ColumnInfo(name = "support_text") String supportText = "";

    static HistoryTransactionEntity fromRecord(XbsConfigTransaction.Record record) {
        HistoryTransactionEntity entity = new HistoryTransactionEntity();
        entity.id = RunRecord.validRunId(record.id) ? record.id.trim() : "";
        entity.status = RunRecord.line(record.status, 64);
        entity.createdAt = Math.max(0L, record.createdAt);
        entity.updatedAt = Math.max(0L, record.updatedAt);
        entity.reload = record.reload;
        entity.snapshotsComplete = record.snapshotsComplete;
        entity.entryCount = Math.max(0, record.entryCount);
        entity.message = RunRecord.line(record.message, MAX_MESSAGE_CHARS);
        entity.error = RunRecord.line(record.error, MAX_MESSAGE_CHARS);
        entity.supportText = boundedSupportText(record.supportText());
        return entity;
    }

    static String boundedSupportText(String value) {
        if (value == null) return "";
        return value.length() <= MAX_SUPPORT_TEXT_CHARS ? value : OVERSIZED_SUPPORT_TEXT;
    }
}
