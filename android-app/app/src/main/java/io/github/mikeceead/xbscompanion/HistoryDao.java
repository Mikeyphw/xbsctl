package io.github.mikeceead.xbscompanion;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
interface HistoryDao {
    @Query("SELECT * FROM run_history ORDER BY CASE WHEN finished_at > 0 THEN finished_at ELSE started_at END DESC")
    List<HistoryRunEntity> allRuns();

    @Query("SELECT * FROM run_history WHERE run_id = :runId LIMIT 1")
    HistoryRunEntity findRun(String runId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertRun(HistoryRunEntity entity);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertRuns(List<HistoryRunEntity> entities);

    @Query("DELETE FROM run_history WHERE run_id IN (:runIds)")
    void deleteRuns(List<String> runIds);

    @Query("UPDATE run_history SET pinned = :pinned WHERE run_id = :runId")
    void setRunPinned(String runId, boolean pinned);

    @Query("DELETE FROM run_history WHERE run_id = :runId AND status NOT IN ('running', 'cancelling')")
    int deleteFinishedRun(String runId);

    @Query("DELETE FROM run_history WHERE status NOT IN ('running', 'cancelling') AND pinned = 0")
    void clearFinishedRuns();

    @Query("DELETE FROM run_history")
    void clearRuns();

    @Query("SELECT COUNT(*) FROM run_history")
    int runCount();

    @Query("SELECT COUNT(*) FROM run_history WHERE status IN ('running', 'cancelling')")
    int activeRunCount();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertTransaction(HistoryTransactionEntity entity);

    @Query("SELECT * FROM config_transaction_history ORDER BY updated_at DESC LIMIT 1")
    HistoryTransactionEntity latestTransaction();

    @Query("DELETE FROM config_transaction_history WHERE id NOT IN (SELECT id FROM config_transaction_history ORDER BY updated_at DESC LIMIT :keep)")
    void trimTransactions(int keep);

    @Query("SELECT COUNT(*) FROM config_transaction_history")
    int transactionCount();

    @Query("SELECT * FROM history_metadata WHERE `key` = :key LIMIT 1")
    HistoryMetadataEntity metadata(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putMetadata(HistoryMetadataEntity entity);
}
