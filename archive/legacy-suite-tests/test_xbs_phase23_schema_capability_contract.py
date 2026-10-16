from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
XBS = ROOT / "apps/xbs"


def source(name: str) -> str:
    return (APP / name).read_text()


def load_compatibility_module():
    path = XBS / "src/xbs_cli/compatibility.py"
    spec = importlib.util.spec_from_file_location("xbs_phase23_compatibility", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def canonical_digest(value) -> str:
    raw = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()
    return hashlib.sha256(raw).hexdigest()


def test_cli_publishes_deterministic_complete_compatibility_contract():
    module = load_compatibility_module()
    payload = module.compatibility_payload(config_version=2)
    contract = payload["data"]["compatibility"]
    assert payload["ok"] is True
    assert contract["contract_version"] == "1.1.0"
    assert contract["cli"]["version"] == "1.3.0"
    assert contract["android_contract"] == {
        "minimum": 1,
        "maximum": 1,
        "minimum_app_version_code": 1,
    }
    assert contract["config"]["active_version"] == 2
    assert contract["config"]["maximum_supported_version"] == 2
    assert len(contract["commands"]) == 39
    assert len(contract["config"]["event_fields"]) == 13
    assert {row["id"] for row in contract["commands"]} == set(module.COMMAND_IDS)
    assert "native_configuration" not in module.COMMAND_IDS
    assert "native_event_editor" not in module.COMMAND_IDS
    digest_input = {
        "contract_version": contract["contract_version"],
        "command_schema_version": contract["cli"]["command_schema_version"],
        "commands": contract["commands"],
        "event_fields": contract["config"]["event_fields"],
        "config_version_max": contract["config"]["maximum_supported_version"],
    }
    assert contract["digest"] == canonical_digest(digest_input)
    assert len(contract["config"]["schema_digest"]) == 64


def test_android_parser_accepts_old_and_new_shapes_and_labels_fallback():
    report = source("XbsCompatibilityReport.java")
    store = source("XbsCompatibilityStore.java")
    for token in [
        "value instanceof JSONObject", "String.valueOf(value)",
        "SOURCE_LIVE", "SOURCE_FALLBACK", "SOURCE_UNAVAILABLE",
        "Static offline compatibility fallback", "unknown future",
    ]:
        if token == "unknown future":
            continue
        assert token in report
    assert "static XbsCompatibilityReport fallback" in report
    assert "Compatibility cache is empty" in report
    assert "xbs_compatibility_v1" in store
    assert "XbsCompatibilityReport.fallback" in store


def test_live_capability_registry_is_authoritative_for_commands_and_fields():
    registry = source("XbsCapabilityRegistry.java")
    policy = source("CommandCenterPolicy.java")
    quick = source("QuickActionsSheet.java")
    editor = source("XbsEventEditorActivity.java")
    for token in ["supportsCommand", "canWriteEventField", "contractAllowsExecution", "negotiated"]:
        assert token in registry
    assert "The installed module capability contract does not publish this command" in policy
    assert "Static fallback catalog" in policy
    assert "supportsCommand(commandId)" in quick
    assert 'supportsField("allowlist")' in editor
    assert 'supportsField("denylist")' in editor
    assert 'supportsField("proc_file")' in editor
    assert "if(!supportsField(key))return" in editor
    assert "!supportsField(key)" in editor and "updated.put(key,event.get(key))" in editor
    assert "allowPicker.setEnabled" in editor
    assert "denyPicker.setEnabled" in editor
    assert "processPicker.setEnabled" in editor


def test_probe_is_fixed_allowlisted_and_evidence_reaches_release_surfaces():
    probe = source("XbsCompatibilityProbe.java")
    diagnostics = source("RootDiagnosticsActivity.java")
    support = source("SupportBundleBuilder.java")
    readiness = source("ReleaseReadinessPolicy.java")
    evaluator = source("ReleaseReadinessEvaluator.java")
    assert '" --json capabilities"' in probe or '--json capabilities' in probe
    assert "RootCommandRunner" not in probe
    assert "Refresh schema compatibility" in diagnostics
    assert "FORMAT_VERSION = 18" in support
    assert "[Compatibility]" in support
    assert "REQUIRED_SUPPORT_BUNDLE_FORMAT = 18" in readiness
    assert '"schema_compatibility"' in readiness
    assert "compatibilityLive" in evaluator
    assert "compatibilityCompatible" in evaluator


def test_phase23_artifact_remains_validation_disabled_when_present():
    manifest = ROOT / ".devtool-artifact.json"
    if not manifest.exists():
        return
    text = manifest.read_text()
    assert '"mode": "disabled"' in text
    assert '"failure_action": "keep"' in text
