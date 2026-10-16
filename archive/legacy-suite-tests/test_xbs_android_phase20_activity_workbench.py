from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app"
JAVA = APP / "app/src/main/java/io/github/mikeceead/xbscompanion"
TEST_JAVA = APP / "app/src/test/java/io/github/mikeceead/xbscompanion"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_activity_workbench_is_registered_and_reachable():
    manifest = read(APP / "app/src/main/AndroidManifest.xml")
    main = read(JAVA / "MainActivity.java")
    notification = read(JAVA / "NotificationController.java")
    activity = read(JAVA / "ActivityWorkbenchActivity.java")
    assert '.ActivityWorkbenchActivity' in manifest
    assert 'Open Activity workbench' in main
    assert 'new Intent(this,ActivityWorkbenchActivity.class)' in main
    assert 'new Intent(context, ActivityWorkbenchActivity.class)' in notification
    assert 'Search, classify, pin, select, export' in activity


def test_room_v2_migration_adds_durable_pins_without_destructive_fallback():
    database = read(JAVA / "XbsHistoryDatabase.java")
    entity = read(JAVA / "HistoryRunEntity.java")
    dao = read(JAVA / "HistoryDao.java")
    record = read(JAVA / "RunRecord.java")
    assert 'version = 2' in database
    assert 'MIGRATION_1_2' in database
    assert 'ALTER TABLE run_history ADD COLUMN pinned' in database
    assert '.addMigrations(MIGRATION_1_2)' in database
    assert 'fallbackToDestructiveMigration' not in database
    assert 'boolean pinned' in entity
    assert 'record.pinned = pinned' in entity
    assert 'void setRunPinned' in dao
    assert 'boolean pinned=false' in record


def test_activity_query_supports_search_problem_source_time_and_pin_filters():
    query = read(JAVA / "ActivityQuery.java")
    for marker in (
        'enum Status', 'enum Window', 'pinnedOnly', 'haystack(record)',
        'isProblem(RunRecord record)', 'nextSource', 'applyEvents',
    ):
        assert marker in query
    assert 'record.stdout' in query
    assert 'record.stderr' in query
    assert 'record.error' in query


def test_bulk_pruning_protects_active_and_pinned_records():
    dao = read(JAVA / "HistoryDao.java")
    store = read(JAVA / "XbsHistoryStore.java")
    retention = read(JAVA / "RunRetentionPolicy.java")
    activity = read(JAVA / "ActivityWorkbenchActivity.java")
    assert "AND pinned = 0" in dao
    assert 'RunFilter.isActive(record) || record.pinned' in store
    assert 'pass == 0 ? RunFilter.isActive(record) : pass == 1 ? record.pinned' in retention
    assert 'Pinned and active records are protected.' in activity
    assert 'deleteFinishedRun' in dao


def test_selection_export_is_explicit_and_bounded():
    activity = read(JAVA / "ActivityWorkbenchActivity.java")
    formatter = read(JAVA / "ActivityExportFormatter.java")
    assert 'Export selected' in activity
    assert 'Share visible results' in activity
    assert 'MAX_OUTPUT = 6000' in formatter
    assert 'MAX_EVENT_PAYLOAD = 1200' in formatter
    assert 'formatEvents' in formatter
    assert 'pinned=' in formatter
    assert 'selectedRuns(all)' in activity


def test_retention_is_configurable_and_bounded():
    settings = read(JAVA / "AppSettings.java")
    policy = read(JAVA / "RunRetentionPolicy.java")
    store = read(JAVA / "XbsHistoryStore.java")
    assert 'historyRetention(Context context)' in settings
    assert 'nextHistoryRetention' in settings
    assert 'MAX_RECORDS = 240' in policy
    assert 'Math.max(30, Math.min(MAX_RECORDS, requestedLimit))' in policy
    assert 'AppSettings.historyRetention(app)' in store


def test_support_and_release_contracts_advance_to_format_v8():
    support = read(JAVA / "SupportBundleBuilder.java")
    policy = read(JAVA / "ReleaseReadinessPolicy.java")
    assert 'FORMAT_VERSION = 18' in support
    assert 'REQUIRED_SUPPORT_BUNDLE_FORMAT = 18' in policy
    assert 'retention=' in support
    assert 'pinned=' in support


def test_java_regressions_cover_query_retention_events_and_export():
    test = read(TEST_JAVA / "ActivityQueryTest.java")
    for marker in (
        'searchCoversCommandAndOutputEvidence',
        'problemClassificationIncludesTimeoutCancellationAndErrorEvidence',
        'sourceWindowAndPinnedFiltersCompose',
        'eventProblemFilterUsesImportantEvidence',
        'pinnedRetentionSurvivesOlderThanUnpinnedRecords',
        'selectedExportIsBoundedAndIncludesPinEvidence',
    ):
        assert marker in test
