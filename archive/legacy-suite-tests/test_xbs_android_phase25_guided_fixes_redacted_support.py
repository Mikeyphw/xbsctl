from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app/app"
JAVA = APP / "src/main/java/io/github/mikeceead/xbscompanion"

def read(path):
    return path.read_text()

def test_guided_fixes_are_private_explicit_and_allowlisted():
    manifest = read(APP / "src/main/AndroidManifest.xml")
    policy = read(JAVA / "GuidedFixPolicy.java")
    activity = read(JAVA / "GuidedFixActivity.java")
    executor = read(JAVA / "GuidedFixExecutor.java")
    assert '<activity android:name=".GuidedFixActivity" android:exported="false" />' in manifest
    for action in ["REFRESH_ROOT_EVIDENCE", "REPAIR_STORAGE", "INITIALIZE_HISTORY", "RECOVER_TRANSACTION", "REPAIR_NOTIFICATIONS", "RESTART_MONITOR"]:
        assert action in policy
    assert "confirmationRequired" in policy
    assert "Nothing runs automatically" in activity
    assert "new AlertDialog.Builder" in activity
    assert "GuidedFixExecutor.execute" in activity
    assert "RootDiagnosticsProbe.repairStoragePaths" in executor
    assert "XbsHistoryStore.status" in executor
    assert "record.needsRecovery()" in executor
    assert "record.canRollback()" in executor
    assert "rollbackLatest" not in executor
    for forbidden in ["chmod 777", "chmod -R", "Runtime.getRuntime().exec", "arbitrary shell"]:
        assert forbidden not in executor

def test_debug_health_remains_passive_and_only_opens_guided_flow():
    evaluator = read(JAVA / "DebugHealthEvaluator.java")
    activity = read(JAVA / "DebugWorkbenchActivity.java")
    body = evaluator[evaluator.index("static DebugHealthReport evaluate"):evaluator.index("private static boolean runtimeEvidencePresent")]
    for forbidden in ["RootDiagnosticsProbe.collect", "GuidedFixExecutor", "XbsHistoryStore.status(", "RootCommandRunner"]:
        assert forbidden not in body
    assert 'GuidedFixActivity.open(this, item.id)' in activity

def test_support_bundle_is_always_redacted_and_versioned():
    builder = read(JAVA / "SupportBundleBuilder.java")
    redactor = read(JAVA / "SupportRedactor.java")
    assert "FORMAT_VERSION = 18" in builder
    assert "SupportRedactor.redact" in builder
    assert '"\\n[Redaction]\\n"' in builder
    assert "GuidedFixStore.cached" in builder
    for token in ["AUTHORIZATION", "SECRET", "PRIVATE_PATH", "EMAIL", "IPV4", "IPV6_CANDIDATE", "PACKAGE", "UUID", "MAX_INPUT_CHARS"]:
        assert token in redactor
    assert "buildRaw" not in builder
    assert "raw support bundle" not in builder.lower()

def test_release_gate_tracks_phase25_sources():
    gradle = read(APP / "build.gradle")
    for name in ["GuidedFixPolicy.java", "GuidedFixExecutor.java", "GuidedFixActivity.java", "SupportRedactor.java"]:
        assert name in gradle


def test_redaction_policy_covers_adversarial_values():
    src = read(JAVA / "SupportRedactor.java")
    assert "POLICY_VERSION = 2" in src
    assert "AUTHORIZATION" in src
    assert "PRIVATE_PATH" in src
    assert "IPV6_CANDIDATE" in src
    assert "private static final Pattern PACKAGE" in src
    assert r"[a-zA-Z0-9_]*\\.)+" in src
    test = read(APP / "src/test/java/io/github/mikeceead/xbscompanion/SupportRedactorTest.java")
    for token in [
        "redactsEveryAuthorizationSchemeAndQuotedMultiwordSecrets",
        "redactsTwoComponentPackagesIpv6AndModernUuidVersions",
        "Mike-tax-return.pdf",
        "Authorization: Basic",
    ]:
        assert token in test
