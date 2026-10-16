from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"


def source(name: str) -> str:
    return (APP / name).read_text()


def test_health_model_has_stable_severity_evidence_and_bounded_contracts():
    report = source("DebugHealthReport.java")
    policy = source("DebugHealthPolicy.java")
    for token in [
        "FORMAT_VERSION = 1", "PASS = 0", "WARNING = 1", "BLOCKER = 2", "UNKNOWN = 3",
        "EVIDENCE_CURRENT", "EVIDENCE_STALE", "EVIDENCE_MISSING", "MAX_ITEMS = 24",
        "String supportText()", "JSONObject toJson()", "static DebugHealthReport fromJson",
    ]:
        assert token in report
    for check in [
        '"root_evidence"', '"root_access"', '"xbs_binaries"', '"schema_compatibility"',
        '"managed_storage"', '"history_store"', '"config_transaction"', '"notifications"',
        '"command_service"', '"event_monitor"', '"runtime_state"', '"process_visibility"',
    ]:
        assert check in policy
    assert "evidenceState(long now" in policy
    assert "transactionSeverity" in policy


def test_evaluator_is_cached_local_and_never_implicitly_requests_root_or_proc():
    evaluator = source("DebugHealthEvaluator.java")
    for token in [
        "RootDiagnosticsStore.cached", "XbsCompatibilityStore.cached", "XbsHistoryStore.peekStatus",
        "NotificationController.readiness", "XbsStateStore.current", "ProcessRepository.cached",
        "RunStore.peekActiveCount", "RootCommandService.isServiceActive", "EventMonitorService.isServiceActive",
        "DebugHealthStore.save",
    ]:
        assert token in evaluator
    for forbidden in [
        "XbsRootApi.checkRoot", "RootDiagnosticsProbe.run", "ProcessRepository.load",
        "XbsCompatibilityProbe.run", "RootCommandRunner", "runRoot", "request root",
    ]:
        assert forbidden not in evaluator


def test_workbench_is_read_only_has_source_navigation_and_is_manifest_private():
    activity = source("DebugWorkbenchActivity.java")
    manifest = (ROOT / "apps/xbs/android-app/app/src/main/AndroidManifest.xml").read_text()
    for token in [
        "Opening this screen never requests root", "Re-evaluate cached evidence",
        "Open Root Diagnostics", "Open Activity Workbench", "Open Process Monitor",
        "Open Release Readiness", "Copy health snapshot", "Copy redacted support bundle",
    ]:
        assert token in activity
    for forbidden in ["XbsRootApi", "RootCommandRunner", "ProcessRepository.load", "XbsCompatibilityProbe"]:
        assert forbidden not in activity
    assert '<activity android:name=".DebugWorkbenchActivity" android:exported="false" />' in manifest


def test_debug_health_reaches_settings_root_release_and_support_bundle_v11():
    settings = source("MoreSettingsActivity.java")
    hub = source("SettingsHubView.java")
    diagnostics = source("RootDiagnosticsActivity.java")
    release = source("ReleaseReadinessActivity.java")
    support = source("SupportBundleBuilder.java")
    policy = source("ReleaseReadinessPolicy.java")
    build = (ROOT / "apps/xbs/android-app/app/build.gradle").read_text()
    assert "Open Debug Workbench" in settings
    assert "DebugHealthStore.cached" in hub
    assert "Open Debug Workbench" in diagnostics
    assert "Open Debug Workbench" in release
    assert "FORMAT_VERSION = 18" in support
    assert "[Debug health]" in support
    assert "DebugHealthStore.cached(context).supportText()" in support
    assert "REQUIRED_SUPPORT_BUNDLE_FORMAT = 18" in policy
    for name in [
        "DebugHealthReport.java", "DebugHealthPolicy.java", "DebugHealthEvaluator.java",
        "DebugHealthStore.java", "DebugWorkbenchActivity.java",
    ]:
        assert name in build


def test_phase24_unit_tests_cover_missing_stale_blocking_and_warning_states():
    test = (ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion/DebugHealthPolicyTest.java").read_text()
    for token in [
        "healthyEvidenceProducesStableHealthyReport",
        "missingCachedEvidenceIsUnknownNotFabricatedFailure",
        "staleRootEvidenceIsNotTreatedAsCurrentTruth",
        "freshRootAndCompatibilityFailuresBlock",
        "unsafeTransactionAndOrphanedRunBlock",
        "userControlledAndPartialEvidenceRemainWarnings",
        "supportTextIsBoundedAndUsesStableIdentifiers",
    ]:
        assert token in test


def test_phase24_artifact_policy_is_disabled_when_manifest_is_present():
    manifest_path = ROOT / ".devtool-artifact.json"
    if not manifest_path.exists():
        return
    text = manifest_path.read_text()
    assert '"mode": "disabled"' in text
    assert '"failure_action": "keep"' in text


def test_evaluator_uses_transitively_passive_history_and_transaction_accessors():
    evaluator = source("DebugHealthEvaluator.java")
    history = source("XbsHistoryStore.java")
    transactions = source("XbsConfigTransactionStore.java")
    runs = source("RunStore.java")
    instrumented = (ROOT / "apps/xbs/android-app/app/src/androidTest/java/io/github/mikeceead/xbscompanion/DebugHealthReadOnlyInstrumentedTest.java").read_text()
    assert "XbsHistoryStore.peekStatus(app)" in evaluator
    assert "XbsConfigTransactionStore.peekSupportText(app)" in evaluator
    assert "RunStore.peekActiveCount(app)" in evaluator
    evaluator_body = evaluator[evaluator.index("static DebugHealthReport evaluate"):evaluator.index("private static boolean runtimeEvidencePresent")]
    for forbidden in ["XbsHistoryStore.status(", "XbsConfigTransactionStore.supportText(", "RunStore.activeCount("]:
        assert forbidden not in evaluator_body
    peek_history = history[history.index("static XbsHistoryStatus peekStatus"):history.index("private static XbsHistoryDatabase ensure")]
    for forbidden in ["ensure(", "XbsHistoryDatabase.get", "migrateLegacy(", "legacy.edit("]:
        assert forbidden not in peek_history
    peek_transaction = transactions[transactions.index("static String peekSupportText"):transactions.index("static String supportText")]
    assert "latestTransactionSupport" not in peek_transaction
    assert "XbsHistoryStore.peekStatus(context).activeRunCount" in runs
    for token in ["suite_runs", "config_transaction_summary_v1", "databaseSnapshot", "DebugHealthEvaluator.evaluate(app)", "assertDatabaseSnapshotEquals"]:
        assert token in instrumented
