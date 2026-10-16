from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app"
JAVA = APP / "app/src/main/java/io/github/mikeceead/xbscompanion"


def text(path: Path) -> str:
    return path.read_text()


def test_module_publishes_and_verifies_canonical_hmac_envelopes():
    bridge = text(ROOT / "apps/xbs/src/xbs_cli/event_bridge.py")
    assert 'ALGORITHM = "hmac-sha256"' in bridge
    assert "hmac.compare_digest" in bridge
    assert "canonical_json" in bridge
    assert "os.replace" in bridge and "os.fsync" in bridge
    assert "0o600" in bridge
    assert "key_id" in bridge and "sequence" in bridge and "issued_at" in bridge
    assert "payload" in bridge and "event" in bridge and "status" in bridge


def test_daemon_publication_is_non_fatal_and_mirrored():
    canonical = text(ROOT / "apps/xbs/system/bin/XtremeBSd")
    mirror = text(ROOT / "apps/xbs/module/system/bin/XtremeBSd")
    assert canonical == mirror
    assert "publish_authenticated_event_bridge" in canonical
    assert "-m xbs_cli.event_bridge publish --quiet" in canonical
    assert ">/dev/null 2>&1 || true" in canonical
    assert canonical.count("publish_authenticated_event_bridge") >= 3


def test_android_uses_fixed_root_verifier_and_explicit_fallback():
    reader = text(JAVA / "EventBridgeReader.java")
    root = text(JAVA / "XbsRootApi.java")
    assert "--json event-bridge" in reader
    assert "XbsRootApi.readLegacySnapshot()" in reader
    assert "EventBridgePolicy.evaluate" in reader
    assert "EventBridgeStore.accept" in reader
    assert "EventBridgePolicy.fallbackAllowed" in reader
    assert "rejectSecurityFailure" in reader
    assert "XbsSnapshot.unavailable" in reader
    assert "runRootLarge(verifiedCommand(), 20)" in reader
    assert 'machineOutput.isEmpty() && output.exitCode != 0' in reader
    assert 'EventBridgeEnvelope.failure("verifier_unavailable"' in reader
    assert "readSnapshot(Context context)" in root
    assert "return EventBridgeReader.read(context)" in root
    for forbidden in ["String.format", "getIntent()", "getStringExtra", "Runtime.getRuntime().exec"]:
        assert forbidden not in reader


def test_replay_policy_and_key_rotation_are_app_owned():
    policy = text(JAVA / "EventBridgePolicy.java")
    assert '"replay"' in policy
    assert '"equivocation"' in policy
    assert "fallbackAllowed" in policy
    assert '"key_rotated"' in policy
    assert "MAX_FUTURE_SKEW_MS" in policy and "MAX_AGE_MS" in policy
    store = text(JAVA / "EventBridgeStore.java")
    assert 'PREFS = "event_bridge_cursor_v1"' in store
    assert "putLong(SEQUENCE" in store and "putString(DIGEST" in store


def test_health_and_release_read_only_evidence_does_not_probe_root():
    evaluator = text(JAVA / "DebugHealthEvaluator.java")
    assert "EventBridgeStore.cached(app)" in evaluator
    assert "EventBridgeReader" not in evaluator
    assert "XbsRootApi.runRoot" not in evaluator
    policy = text(JAVA / "DebugHealthPolicy.java")
    assert '"event_bridge"' in policy
    assert "eventBridgeSecurityFailure" in policy
    release = text(JAVA / "ReleaseReadinessEvaluator.java")
    assert "EventBridgeStore.cached(app)" in release


def test_support_bundle_is_redacted_v13_and_never_exposes_key_material():
    builder = text(JAVA / "SupportBundleBuilder.java")
    assert "FORMAT_VERSION = 18" in builder
    assert '"\\n[Event bridge]\\n"' in builder
    assert "EventBridgeStore.cached(context).supportText()" in builder
    assert "SupportRedactor.redact" in builder
    for source in JAVA.glob("EventBridge*.java"):
        content = text(source)
        assert "event_bridge_hmac_v1.key" not in content
        assert "XBS_EVENT_BRIDGE_KEY" not in content


def test_capability_contract_advertises_trust_boundary_without_catalog_command():
    compatibility = text(ROOT / "apps/xbs/src/xbs_cli/compatibility.py")
    assert '"authenticated_event_bridge": True' in compatibility
    assert '"preferred_transport": "root_verified_envelope"' in compatibility
    assert '"fallback_transport": "bounded_direct_poll"' in compatibility
    assert "non-root runtime-file tampering" in compatibility
    command_ids = compatibility.split("COMMAND_IDS:", 1)[1].split("COMMAND_PATHS:", 1)[0]
    assert "event-bridge" not in command_ids


def test_fallback_is_explicitly_allowlisted_and_replay_cursor_is_durable():
    policy = text(JAVA / "EventBridgePolicy.java")
    assert 'return !securityFailure(code);' not in policy
    for code in ["missing", "unreadable", "stale_envelope", "xbs_missing", "verifier_usage"]:
        assert f'"{code}".equals(value)' in policy
    reader = text(JAVA / "EventBridgeReader.java")
    assert 'fallback(app, now, "invalid_verified_snapshot"' not in reader
    assert '"cursor_persist_failed"' in reader
    store = text(JAVA / "EventBridgeStore.java")
    assert '.commit();' in store
    assert '.apply();' not in store


def test_root_bridge_serializes_publication_and_rejects_insecure_state():
    src = text(ROOT / "apps/xbs/src/xbs_cli/event_bridge.py")
    for token in ["fcntl.flock", "_publication_lock", "os.lstat", "O_NOFOLLOW", "_validate_private_file", "_validate_private_parent"]:
        assert token in src
    assert 'except (ValueError, UnicodeDecodeError, EventBridgeError)' not in src
    assert 'if mode != 0o600: os.chmod' not in src
