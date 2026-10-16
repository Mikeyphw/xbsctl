from __future__ import annotations

import importlib.util
import os
import pytest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TEST_JAVA = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"
XBS = ROOT / "apps/xbs"


def source(name: str) -> str:
    return (JAVA / name).read_text(encoding="utf-8")


def test_guided_fix_surface_is_private_explicit_and_non_automatic():
    manifest = (ROOT / "apps/xbs/android-app/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    policy = source("GuidedFixPolicy.java")
    activity = source("GuidedFixActivity.java")
    executor = source("GuidedFixExecutor.java")
    store = source("GuidedFixStore.java")
    assert '<activity android:name=".GuidedFixActivity" android:exported="false" />' in manifest
    for action in ["REFRESH_ROOT_EVIDENCE", "REPAIR_STORAGE", "INITIALIZE_HISTORY", "RECOVER_TRANSACTION", "REPAIR_NOTIFICATIONS", "RESTART_MONITOR"]:
        assert action in policy
    assert "confirmationRequired" in policy
    assert "Nothing runs automatically" in activity
    assert "new AlertDialog.Builder" in activity
    assert "GuidedFixExecutor.execute" in activity
    assert "GuidedFixResult" in store
    assert "rollbackLatest" not in executor
    for forbidden in ["chmod 777", "chmod -R", "Runtime.getRuntime().exec", "arbitrary shell"]:
        assert forbidden not in executor


def test_support_bundle_redaction_policy_is_bounded_versioned_and_adversarial():
    builder = source("SupportBundleBuilder.java")
    redactor = source("SupportRedactor.java")
    junit = (TEST_JAVA / "SupportRedactorTest.java").read_text(encoding="utf-8")
    assert "FORMAT_VERSION = 18" in builder
    assert "SupportRedactor.redact" in builder
    assert '"\\n[Redaction]\\n"' in builder
    assert "GuidedFixStore.cached" in builder
    assert "MAX_INPUT_CHARS = 256_000" in redactor
    for token in ["AUTHORIZATION", "SECRET", "PRIVATE_PATH", "EMAIL", "IPV4", "IPV6_CANDIDATE", "PACKAGE", "UUID", "POLICY_VERSION = 2"]:
        assert token in redactor
    for token in ["redactsEveryAuthorizationSchemeAndQuotedMultiwordSecrets", "redactsTwoComponentPackagesIpv6AndModernUuidVersions", "Mike-tax-return.pdf"]:
        assert token in junit
    assert "buildRaw" not in builder


def test_authenticated_event_bridge_is_canonical_locked_and_private():
    bridge = (XBS / "src/xbs_cli/event_bridge.py").read_text(encoding="utf-8")
    assert 'ALGORITHM = "hmac-sha256"' in bridge
    assert "canonical_json" in bridge
    assert "hmac.compare_digest" in bridge
    assert "fcntl.flock" in bridge
    assert "os.replace" in bridge and "os.fsync" in bridge
    assert "O_NOFOLLOW" in bridge
    assert "_validate_private_file" in bridge and "_validate_private_parent" in bridge
    assert "def _publication_lock" in bridge
    assert "if mode != 0o600: os.chmod" not in bridge
    assert "except (ValueError, UnicodeDecodeError, EventBridgeError)" not in bridge


def test_android_event_bridge_fails_closed_and_bounds_cache_and_machine_output():
    reader = source("EventBridgeReader.java")
    envelope = source("EventBridgeEnvelope.java")
    report = source("EventBridgeReport.java")
    store = source("EventBridgeStore.java")
    policy = source("EventBridgePolicy.java")
    assert "--json event-bridge" in reader
    assert "XbsRootApi.readLegacySnapshot()" in reader
    assert "EventBridgePolicy.evaluate" in reader
    assert "EventBridgeStore.accept" in reader
    assert "rejectSecurityFailure" in reader
    assert "MAX_MACHINE_OUTPUT_CHARS = 512_000" in envelope
    assert "oversized_verifier_output" in envelope
    assert "MAX_CACHE_CHARS = 16 * 1024" in store
    assert "raw.length() > MAX_CACHE_CHARS" in store
    assert "stringValue(JSONObject json, String key, String fallback)" in report
    assert 'return !fallbackAllowed(code);' in policy
    for code in ["missing", "unreadable", "stale_envelope", "xbs_missing", "verifier_usage"]:
        assert f'"{code}".equals(value)' in policy
    for forbidden in ["String.format", "getIntent()", "getStringExtra", "Runtime.getRuntime().exec"]:
        assert forbidden not in reader


def test_daemon_publication_is_non_fatal_mirrored_and_capability_only():
    canonical = (XBS / "system/bin/XtremeBSd").read_text(encoding="utf-8")
    mirror = (XBS / "module/system/bin/XtremeBSd").read_text(encoding="utf-8")
    compatibility = (XBS / "src/xbs_cli/compatibility.py").read_text(encoding="utf-8")
    assert canonical == mirror
    assert "publish_authenticated_event_bridge" in canonical
    assert "-m xbs_cli.event_bridge publish --quiet" in canonical
    assert ">/dev/null 2>&1 || true" in canonical
    assert '"authenticated_event_bridge": True' in compatibility
    assert '"preferred_transport": "root_verified_envelope"' in compatibility
    assert '"fallback_transport": "bounded_direct_poll"' in compatibility
    assert "non-root runtime-file tampering" in compatibility
    command_ids = compatibility.split("COMMAND_IDS:", 1)[1].split("COMMAND_PATHS:", 1)[0]
    assert "event-bridge" not in command_ids


def test_debug_health_and_release_read_event_bridge_passively():
    evaluator = source("DebugHealthEvaluator.java")
    release = source("ReleaseReadinessEvaluator.java")
    workbench = source("DebugWorkbenchActivity.java")
    assert "EventBridgeStore.cached(app)" in evaluator
    assert "EventBridgeReader" not in evaluator
    assert "XbsRootApi.runRoot" not in evaluator
    assert "EventBridgeStore.cached(app)" in release
    assert "GuidedFixActivity.open(this, item.id)" in workbench


def test_v2_event_bridge_strict_types_and_top_level_error_mapping():
    envelope = source("EventBridgeEnvelope.java")
    store = source("EventBridgeStore.java")
    bridge = (XBS / "src/xbs_cli/event_bridge.py").read_text(encoding="utf-8")
    junit = (TEST_JAVA / "EventBridgeEnvelopeTest.java").read_text(encoding="utf-8")
    assert "topLevelVerifierFailure" in envelope
    assert 'requiredString(bridge, "algorithm"' in envelope
    assert 'requiredLong(bridge, "sequence"' in envelope
    assert "authenticatedMetadataUsesStrictJsonTypes" in junit
    assert "topLevelVerifierErrorsPreserveFallbackReasonCode" in junit
    assert "normalizedHex(keyId, 16)" in store
    assert "safeString(prefs, KEY_ID)" in store
    assert "encoded.length() > MAX_CACHE_CHARS" in store
    assert "_validate_trusted_source_file" in bridge
    assert "Bridge source has the wrong owner" in bridge


def test_v2_root_bridge_rejects_group_or_world_writable_source_files(tmp_path):
    spec = importlib.util.spec_from_file_location("event_bridge_under_test", XBS / "src/xbs_cli/event_bridge.py")
    bridge = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(bridge)
    event = tmp_path / "event.json"
    status = tmp_path / "status.json"
    event.write_text("{}", encoding="utf-8")
    status.write_text("{}", encoding="utf-8")
    os.chmod(event, 0o666)
    os.chmod(status, 0o600)
    runtime = tmp_path / "runtime"
    rootdir = tmp_path / "root"
    with pytest.raises(bridge.EventBridgeError) as exc:
        bridge.publish(
            event_path=event,
            status_path=status,
            key_path=rootdir / "event_bridge_hmac_v1.key",
            envelope_path=runtime / "event_bridge_v1.json",
            sequence_path=runtime / "event_bridge_v1.seq",
            now=1_800_000_000,
        )
    assert exc.value.code == "source_permissions"
