from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TEST_JAVA = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"


def read(path):
    return path.read_text(encoding="utf-8")


def test_run_detail_formatter_separates_active_status_and_output_blocks():
    src = read(JAVA / "RunDetailFormatter.java")
    assert "RunFilter.isActive(record)" in src
    run_filter = read(JAVA / "RunFilter.java")
    assert '"running".equals(record.status)' in run_filter
    assert '"cancelling".equals(record.status)' in run_filter
    assert 'STDOUT' in src and 'STDERR' in src and 'ERROR' in src
    assert 'timeout ").append(record.timeoutSeconds)' in src


def test_run_store_exposes_active_records_for_ui():
    src = read(JAVA / "RunStore.java")
    assert 'static synchronized List<RunRecord> active(Context c)' in src
    assert 'filtered(c, RunFilter.ACTIVE)' in src
    assert 'static synchronized int activeCount(Context context) { return active(context).size(); }' in src


def test_main_activity_has_active_command_sections_and_cancel_controls():
    src = read(JAVA / "MainActivity.java")
    assert 'activeOverview(c);' in src
    assert 'private void activeCommands(LinearLayout c)' in src
    assert 'private LinearLayout runCard(RunRecord r)' in src
    assert 'RootCommandService.requestCancel(this,r.runId)' in src
    assert 'RunDetailFormatter.detailBody(r)' in src


def test_notification_run_id_reveals_detail_dialog_once():
    src = read(JAVA / "MainActivity.java")
    assert 'private void revealRunFromIntent(View anchor)' in src
    assert 'getIntent().removeExtra("run_id")' in src
    assert 'RunStore.find(this,runId)' in src
    assert 'showRun(record)' in src


def test_java_unit_test_covers_formatter_contract():
    src = read(TEST_JAVA / "RunDetailFormatterTest.java")
    assert 'activeStatusesDriveCancellationControls' in src
    assert 'statusLineShowsSourceMutationAndTimeout' in src
    assert 'detailBodyKeepsStdoutAndStderrSeparate' in src
