package io.github.mikeceead.xbscompanion;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "history_metadata")
final class HistoryMetadataEntity {
    @PrimaryKey @NonNull String key = "";
    String value = "";
    @ColumnInfo(name = "updated_at") long updatedAt;

    HistoryMetadataEntity() {}
    @Ignore
    HistoryMetadataEntity(String key, String value, long updatedAt) {
        this.key = key;
        this.value = value == null ? "" : value;
        this.updatedAt = updatedAt;
    }
}
