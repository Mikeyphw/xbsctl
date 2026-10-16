from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app"
JAVA = APP / "app/src/main/java/io/github/mikeceead/xbscompanion"
TEST_JAVA = APP / "app/src/test/java/io/github/mikeceead/xbscompanion"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_room_database_has_versioned_bounded_history_tables():
    database = read(JAVA / "XbsHistoryDatabase.java")
    dao = read(JAVA / "HistoryDao.java")
    assert "@Database" in database
    assert "version = 2" in database
    assert "MIGRATION_1_2" in database
    assert "ADD COLUMN pinned" in database
    assert "HistoryRunEntity.class" in database
    assert "HistoryTransactionEntity.class" in database
    assert "HistoryMetadataEntity.class" in database
    assert "JournalMode.WRITE_AHEAD_LOGGING" in database
    assert "fallbackToDestructiveMigration" not in database
    assert "DELETE FROM run_history WHERE run_id IN" in dao
    assert "MAX_TRANSACTIONS" not in dao


def test_legacy_shared_preferences_migration_is_idempotent_and_commit_first():
    store = read(JAVA / "XbsHistoryStore.java")
    assert 'MIGRATION_KEY = "legacy_runs_v1"' in store
    assert "database.runInTransaction" in store
    assert "dao.metadata(MIGRATION_KEY)" in store
    assert "dao.putMetadata" in store
    assert "legacy.edit().remove(LEGACY_KEY).apply()" in store
    transaction = store.index("database.runInTransaction")
    assert transaction < store.rindex("legacy.edit().remove(LEGACY_KEY).apply()")
    assert "skipped++" in store
    assert "upsertRuns" in store


def test_run_store_preserves_existing_frontend_contract_over_room():
    run_store = read(JAVA / "RunStore.java")
    assert "XbsHistoryStore.allRuns(context)" in run_store
    assert "XbsHistoryStore.upsertRun(context, value)" in run_store
    assert "XbsHistoryStore.clearFinished(context)" in run_store
    assert "XbsHistoryStore.clearRuns(context)" in run_store
    assert "ACTION_UPDATED" in run_store
    assert "RootCommandService" not in run_store


def test_delete_finished_uses_effectively_final_normalized_run_id_for_lambda_capture():
    store = read(JAVA / "XbsHistoryStore.java")
    assert "final String normalizedRunId = runId.trim();" in store
    assert "record -> normalizedRunId.equals(record.runId)" in store
    assert "deleteFinishedRun(normalizedRunId)" in store
    assert "runId = runId.trim();" not in store[store.index("static boolean deleteFinished(Context context, String runId)"):store.index("static int deleteFinished(Context context, List<String> runIds)")]


def test_configuration_transaction_summaries_are_persisted_but_root_journal_stays_authoritative():
    store = read(JAVA / "XbsConfigTransactionStore.java")
    history = read(JAVA / "XbsHistoryStore.java")
    entity = read(JAVA / "HistoryTransactionEntity.java")
    assert "XbsHistoryStore.recordTransaction(app, record)" in store
    assert "XbsHistoryStore.latestTransactionSupport(app)" in store
    assert "MAX_TRANSACTIONS = 32" in history
    assert "dao.trimTransactions(MAX_TRANSACTIONS)" in history
    assert "support_text" in entity
    assert "TRANSACTION_DIR" not in entity


def test_local_history_evidence_is_visible_in_settings_support_and_release_gate():
    settings_ui = read(JAVA / "MoreSettingsActivity.java")
    support = read(JAVA / "SupportBundleBuilder.java")
    policy = read(JAVA / "ReleaseReadinessPolicy.java")
    evaluator = read(JAVA / "ReleaseReadinessEvaluator.java")
    activity = read(JAVA / "ReleaseReadinessActivity.java")
    assert 'sectionTitle("Room history")' in settings_ui
    assert "history.readyForRelease()" in settings_ui
    assert "FORMAT_VERSION = 18" in support
    assert "appendLocalHistory(app, out)" in support
    assert "[Local history]" in support
    assert '"history_database"' in policy
    assert '"history_migration"' in policy
    assert "XbsHistoryStore.status(app)" in evaluator
    assert "Recheck local history" in activity
    assert "XbsHistoryStore.status(this)" in activity
    gradle = read(APP / "app/build.gradle")
    assert "XbsHistoryDatabase.java" in gradle
    assert "XbsHistoryStore.java" in gradle


def test_java_regressions_cover_history_status_and_release_blockers():
    status_test = read(TEST_JAVA / "XbsHistoryStatusTest.java")
    release_test = read(TEST_JAVA / "ReleaseReadinessPolicyTest.java")
    for marker in (
        "completeHistoryEvidenceIsReleaseReady",
        "databaseFailureIsBoundedAndSingleLine",
        "errorEvidenceIsCappedAtCacheLimit",
        "negativeCountersAreClamped",
    ):
        assert marker in status_test
    assert "unavailableHistoryDatabaseBlocksRelease" in release_test
    assert "incompleteLegacyMigrationBlocksRelease" in release_test
