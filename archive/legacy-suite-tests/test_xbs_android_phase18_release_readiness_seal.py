from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app"
JAVA = APP / "app/src/main/java/io/github/mikeceead/xbscompanion"
TEST_JAVA = APP / "app/src/test/java/io/github/mikeceead/xbscompanion"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_release_gate_has_stable_blocker_warning_and_pass_model():
    report = read(JAVA / "ReleaseReadinessReport.java")
    policy = read(JAVA / "ReleaseReadinessPolicy.java")
    assert "static final int PASS = 0" in report
    assert "static final int WARNING = 1" in report
    assert "static final int BLOCKER = 2" in report
    assert 'return "BLOCKED"' in report
    assert 'return "ATTENTION"' in report
    assert 'return "READY"' in report
    assert "ReleaseReadinessReport evaluate(Evidence evidence)" in policy


def test_readiness_gate_blocks_unsafe_core_runtime_states():
    policy = read(JAVA / "ReleaseReadinessPolicy.java")
    for marker in (
        '"diagnostics_evidence"',
        '"root_access"',
        '"xbs_binaries"',
        '"storage_paths"',
        '"foreground_services"',
        '"command_service"',
        '"config_transaction"',
        '"runtime_state"',
    ):
        assert marker in policy
    assert '"rollback_failed"' not in policy or "transactionSeverity" in policy
    assert "return ReleaseReadinessReport.BLOCKER" in policy


def test_stale_and_partial_evidence_is_explicit_not_silently_fresh():
    policy = read(JAVA / "ReleaseReadinessPolicy.java")
    assert "RUNTIME_STALE_AFTER_MS = 2 * 60_000L" in policy
    assert "PROCESS_STALE_AFTER_MS = 5 * 60_000L" in policy
    assert '"runtime_freshness"' in policy
    assert '"process_visibility"' in policy
    assert "processPartial" in policy
    assert "Diagnostics are stale" in policy


def test_android_evaluator_uses_bounded_existing_caches_without_root_prompt():
    evaluator = read(JAVA / "ReleaseReadinessEvaluator.java")
    assert "RootDiagnosticsStore.cached(app)" in evaluator
    assert "XbsStateStore.current(app)" in evaluator
    assert "ProcessRepository.cached(app)" in evaluator
    assert "XbsConfigTransactionStore.supportText(app)" in evaluator
    assert "RunStore.activeCount(app)" in evaluator
    assert "checkRoot" not in evaluator
    assert "runRoot" not in evaluator


def test_release_readiness_screen_and_guided_navigation_are_registered():
    activity = read(JAVA / "ReleaseReadinessActivity.java")
    settings_ui = read(JAVA / "MoreSettingsActivity.java")
    diagnostics = read(JAVA / "RootDiagnosticsActivity.java")
    manifest = read(APP / "app/src/main/AndroidManifest.xml")
    assert "Re-evaluate cached evidence" in activity
    assert "Run root diagnostics" in activity
    assert "Inspect configuration journal" in activity
    assert "Refresh process evidence" in activity
    assert "Open Release Readiness" in settings_ui
    assert "Open release readiness" in diagnostics
    assert '<activity android:name=".ReleaseReadinessActivity" android:exported="false" />' in manifest


def test_support_bundle_v6_exports_complete_readiness_evidence():
    support = read(JAVA / "SupportBundleBuilder.java")
    assert "FORMAT_VERSION = 18" in support
    assert "appendReleaseReadiness(app, out)" in support
    assert "[Release readiness]" in support
    assert "ReleaseReadinessEvaluator.evaluate(context).supportText()" in support


def test_release_metadata_and_signing_gate_are_sealed_in_gradle():
    gradle = read(APP / "app/build.gradle")
    assert "xbsReleaseVersionCode = 15" in gradle
    assert "xbsReleaseVersionName = '4.2.0'" in gradle
    assert "versionCode xbsReleaseVersionCode" in gradle
    assert "versionName xbsReleaseVersionName" in gradle
    assert "verifyReleaseReadiness" in gradle
    assert "dependsOn tasks.named('verifyReleaseSigning')" in gradle
    assert "task.dependsOn tasks.named('verifyReleaseReadiness')" in gradle
    assert "xbsReleaseTargetSdk = 36" in gradle
    assert "targetSdk xbsReleaseTargetSdk" in gradle


def test_java_regressions_cover_release_gate_edges_and_stable_evidence():
    test = read(TEST_JAVA / "ReleaseReadinessPolicyTest.java")
    for marker in (
        "completeReleaseEvidenceSealsReady",
        "debugBuildIsAttentionNotBlocker",
        "staleDiagnosticsBlockRelease",
        "unsafeTransactionStateBlocksRelease",
        "orphanedActiveRunBlocksRelease",
        "staleRuntimeAndProcessSamplesAreWarnings",
        "transactionStatusParsingIsBoundedAndExact",
        "supportTextContainsStableGateEvidence",
    ):
        assert marker in test
