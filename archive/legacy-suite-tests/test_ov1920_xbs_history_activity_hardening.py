from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TEST = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"


def read(name: str) -> str:
    return (SRC / name).read_text(encoding="utf-8")


def read_test(name: str) -> str:
    return (TEST / name).read_text(encoding="utf-8")


def test_run_history_entities_bound_imported_and_room_evidence():
    run_record = read("RunRecord.java")
    history_entity = read("HistoryRunEntity.java")
    history_store = read("XbsHistoryStore.java")
    transaction_entity = read("HistoryTransactionEntity.java")
    run_store = read("RunStore.java")

    assert "MAX_OUTPUT_CHARS = RunRetentionPolicy.MAX_OUTPUT_CHARS" in run_record
    assert "static boolean validRunId" in run_record
    assert "RunRecord.tail(safe.stdout, RunRetentionPolicy.MAX_OUTPUT_CHARS)" in history_entity
    assert "RunRecord.line(safe.title, RunRecord.MAX_TITLE_CHARS)" in history_entity
    assert 'record.runId = RunRecord.validRunId(runId) ? runId.trim() : ""' in history_entity
    assert "MAX_LEGACY_JSON_CHARS" in history_store
    assert "MAX_LEGACY_RECORDS" in history_store
    assert "hasValidStringRunId(item)" in history_store
    assert "raw instanceof String && RunRecord.validRunId((String) raw)" in history_store
    assert "record == null || !RunRecord.validRunId(record.runId)" in history_store
    assert "!RunRecord.validRunId(runId)" in history_store
    assert "!RunRecord.validRunId(record.id)" in history_store
    assert "value == null || !RunRecord.validRunId(value.runId)" in run_store
    assert "if (!RunRecord.validRunId(runId)) return" in run_store
    assert "MAX_SUPPORT_TEXT_CHARS = 4096" in transaction_entity
    assert "OVERSIZED_SUPPORT_TEXT" in transaction_entity
    assert "boundedSupportText(record.supportText())" in transaction_entity


def test_activity_workbench_query_problem_and_export_bounds_are_source_guarded():
    query = read("ActivityQuery.java")
    export = read("ActivityExportFormatter.java")
    run_filter = read("RunFilter.java")

    assert "MAX_QUERY_CHARS = 160" in query
    assert "MAX_SEARCH_FIELD_CHARS = 4096" in query
    assert "knownSource(normalizedSource) ? normalizedSource : SOURCE_ALL" in query
    assert "\"cancelled\".equals(status)" in query
    assert "\"timeout\".equals(status)" in query
    assert "field(record.stdout)" in query
    assert "field(event.message)" in query
    assert "MAX_RUN_RECORDS = 60" in export
    assert "MAX_EVENT_RECORDS = 120" in export
    assert "MAX_TOTAL_CHARS = 192_000" in export
    assert 'append("records=").append(runs.size()).append(" exported=")' in export
    assert "status(record).equals(\"cancelled\")" in run_filter
    assert "status(record).equals(\"timeout\")" in run_filter


def test_phase_19_20_regression_tests_cover_new_gaps():
    activity_test = read_test("ActivityQueryTest.java")
    history_test = read_test("RunHistoryPolicyTest.java")

    assert "problemClassificationAcceptsStatusOnlyTimeoutAndCancellation" in activity_test
    assert "queryAndExportsAreBounded" in activity_test
    assert "exported=60" in activity_test
    assert "runRecordJsonAndEntitiesBoundLocalHistoryEvidence" in history_test
    assert "transactionEntityBoundsSupportEvidence" in history_test
    assert "roomEntityReadbackRejectsInvalidIdsAndBoundsFields" in history_test
    assert "oversizedTransactionRoomEvidenceFailsClosed" in history_test
