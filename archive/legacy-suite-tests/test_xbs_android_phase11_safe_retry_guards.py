from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TEST = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"


def read(path: Path) -> str:
    return path.read_text()


def test_retry_policy_blocks_active_and_missing_commands():
    text = read(JAVA / "RunRetryPolicy.java")
    assert "Active commands cannot be retried" in text
    assert "Run has no stored command" in text
    assert "RunFilter.isActive(record)" in text


def test_retry_policy_confirms_destructive_replays():
    text = read(JAVA / "RunRetryPolicy.java")
    assert "destructive root command" in text
    assert "requiresConfirmation" in text
    assert "Rerun destructive command from history after confirmation" in text


def test_main_activity_uses_retry_policy():
    text = read(JAVA / "MainActivity.java")
    assert "RunRetryPolicy.evaluate(r)" in text
    assert "decision.requiresConfirmation" in text
    assert "launchRetry(decision)" in text
    assert "RootCommandRunner.runCommand(this,decision.actionId" in text


def test_run_filter_delegates_retryability():
    text = read(JAVA / "RunFilter.java")
    assert "return RunRetryPolicy.canRetry(record);" in text


def test_unit_tests_cover_retry_policy():
    text = read(TEST / "RunRetryPolicyTest.java")
    assert "blocksActiveRuns" in text
    assert "destructiveRunsRequireConfirmation" in text
    assert "retryPrefixIsNotRepeated" in text
