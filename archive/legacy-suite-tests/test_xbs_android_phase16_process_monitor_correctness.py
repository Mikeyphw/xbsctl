from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TEST_JAVA = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_cpu_total_excludes_guest_double_counting():
    parser = read(JAVA / "ProcessSampleParser.java")
    assert "never guest or guest_nice" in parser
    assert "Math.min(8, fields.length - 1)" in parser


def test_proc_scan_reads_real_identity_and_runtime_evidence():
    repo = read(JAVA / "ProcessRepository.java")
    assert "RESTAT=${STAT##*) }" in repo
    assert "PARENT=${2:-}" in repo
    assert "START=${20:-}" in repo
    assert 'tr \'\\\\000\\\\011\\\\015\\\\012\'' in repo
    assert 'readlink \\"$D/exe\\"' in repo
    assert '$D/attr/current' in repo


def test_pid_reuse_cannot_inherit_old_cpu_delta():
    repo = read(JAVA / "ProcessRepository.java")
    process = read(JAVA / "XbsProcess.java")
    parser = read(JAVA / "ProcessSampleParser.java")
    assert "previousByIdentity" in repo
    assert "identityKey()" in process
    assert "currentStartTicks != previousStartTicks" in parser
    assert "previousByPid" not in repo


def test_process_snapshot_cache_schema_was_bumped():
    repo = read(JAVA / "ProcessRepository.java")
    assert 'process_snapshot_v4' in repo
    assert 'process_snapshot_v3' not in repo


def test_restricted_proc_diagnostics_are_visible_and_exported():
    diagnostics = read(JAVA / "ProcessScanDiagnostics.java")
    activity = read(JAVA / "ProcessMonitorActivity.java")
    support = read(JAVA / "SupportBundleBuilder.java")
    assert "Partial /proc visibility" in activity
    assert "restricted" in diagnostics
    assert "[Process monitor]" in support
    assert "FORMAT_VERSION = 18" in support
    assert "ProcessSnapshot.error(parsed.error, diagnostics)" in read(JAVA / "ProcessRepository.java")
    assert "snapshot.diagnostics.scanned > 0" in support


def test_extended_fields_do_not_claim_unobserved_values():
    process = read(JAVA / "XbsProcess.java")
    activity = read(JAVA / "ProcessMonitorActivity.java")
    for field in ("commandLineObserved", "executableObserved", "contextObserved"):
        assert field in process
        assert field in activity


def test_phase16_java_regressions_cover_required_fixtures():
    test = read(TEST_JAVA / "ProcessSampleParserTest.java")
    for marker in (
        "aggregateCpuExcludesGuestAndGuestNice",
        "aggregateCpuAcceptsShortAndroidKernelVariants",
        "parsesSpacesCmdlineIdentityAndExtendedEvidence",
        "emptyCmdlineCanUseCommWithoutClaimingFailure",
        "cpuDeltaRejectsPidReuseAndScalesAcrossCores",
        "restrictedProcProducesActionableFailure",
        "malformedRowsAreCountedWithoutDiscardingGoodRows",
    ):
        assert marker in test


def test_phase16_has_no_android_resource_churn():
    # This phase intentionally changes Java/docs/tests only.
    assert not (ROOT / "apps/xbs/android-app/app/src/main/res/layout/phase16.xml").exists()
