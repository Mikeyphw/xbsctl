package io.github.mikeceead.xbscompanion;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Versioned local database for bounded frontend history. Root-side XBS data remains authoritative. */
@Database(entities = {
    HistoryRunEntity.class,
    HistoryTransactionEntity.class,
    HistoryMetadataEntity.class
}, version = 2, exportSchema = true)
abstract class XbsHistoryDatabase extends RoomDatabase {
    static final String NAME = "xbs_history.db";
    private static volatile XbsHistoryDatabase instance;

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE run_history ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_run_history_pinned_finished_at ON run_history(pinned, finished_at)");
        }
    };

    abstract HistoryDao historyDao();

    static XbsHistoryDatabase get(Context context) {
        XbsHistoryDatabase current = instance;
        if (current != null) return current;
        synchronized (XbsHistoryDatabase.class) {
            current = instance;
            if (current == null) {
                current = Room.databaseBuilder(context.getApplicationContext(), XbsHistoryDatabase.class, NAME)
                    .addMigrations(MIGRATION_1_2)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .setQueryExecutor(XbsHistoryStore.databaseExecutor())
                    .setTransactionExecutor(XbsHistoryStore.databaseExecutor())
                    .build();
                instance = current;
            }
            return current;
        }
    }
}
