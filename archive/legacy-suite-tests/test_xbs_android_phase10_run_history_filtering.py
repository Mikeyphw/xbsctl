from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TEST = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"


def read(path: Path) -> str:
    return path.read_text()


def test_run_filter_contract_exists():
    text = read(JAVA / "RunFilter.java")
    for name in ["ALL", "ACTIVE", "SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "OUTPUT"]:
        assert name in text
    assert "exitCode == 124" in text
    assert "exitCode == 130" in text
    assert "isRetryable" in text


def test_retention_policy_keeps_active_runs():
    text = read(JAVA / "RunRetentionPolicy.java")
    assert "DEFAULT_RECORDS = 120" in text
    assert "MAX_RECORDS = 240" in text
    assert "RunFilter.isActive(record)" in text
    assert "Comparator.comparingLong" in text


def test_activity_exposes_filters_clear_and_retry():
    text = read(JAVA / "MainActivity.java")
    assert "private RunFilter runFilter = RunFilter.ALL" in text
    assert "commandHistory(c)" in text
    assert "filterRow(RunFilter.ALL" in text
    assert "Clear finished" in text
    assert "retryRun" in text
    assert "RunStore.filtered(this,runFilter)" in text


def test_unit_test_covers_history_policy():
    text = read(TEST / "RunHistoryPolicyTest.java")
    assert "filtersSeparateTerminalStates" in text
    assert "retentionKeepsActiveRunsBeforeOlderFinishedRuns" in text
