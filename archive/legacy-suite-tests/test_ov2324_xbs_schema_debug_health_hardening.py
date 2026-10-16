from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
XBS = ROOT / "apps/xbs"


def source(name: str) -> str:
    return (APP / name).read_text(encoding="utf-8")


def load_compatibility_module():
    path = XBS / "src/xbs_cli/compatibility.py"
    spec = importlib.util.spec_from_file_location("xbs_ov2324_compatibility", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_cli_capability_contract_rejects_unsafe_schema_and_properties(tmp_path, monkeypatch):
    module = load_compatibility_module()
    schema = {
        "properties": {
            "events": {
                "additionalProperties": {
                    "properties": {
                        "allowlist": {"type": "array"},
                        "bad-field": {"type": "string"},
                        "9bad": {"type": "string"},
                        "with space": {"type": "string"},
                    }
                }
            }
        }
    }
    (tmp_path / "schema.json").write_text(json.dumps(schema), encoding="utf-8")
    (tmp_path / "module.prop").write_text(
        "id=bad;id\nversion=v-test\nversionCode=not-an-int\n", encoding="utf-8"
    )
    monkeypatch.setenv("XBS_MODULE_DIR", str(tmp_path))

    contract = module.compatibility_payload(config_version="not-an-int")["data"]["compatibility"]

    assert contract["module"]["id"] == "XtremeBS"
    assert contract["module"]["version_code"] == 0
    assert contract["config"]["active_version"] == module.CONFIG_VERSION_MAX
    assert contract["config"]["event_fields"] == ["allowlist"]
    assert len(contract["digest"]) == 64


def test_phase23_android_parser_and_store_are_bounded_and_strict():
    report = source("XbsCompatibilityReport.java")
    store = source("XbsCompatibilityStore.java")
    for token in [
        "MAX_RAW_CHARS = 96 * 1024",
        "Compatibility payload exceeds bounded parser limit",
        "Compatibility \" + label + \" exceeds bounded parser limit",
        "Compatibility cache identifier array exceeds bounded parser limit",
        "private static final Pattern SAFE_ID",
        "ensureArrayBound(commandValues, MAX_COMMAND_IDS, \"command array\")",
        "arrayTooLarge(commandValues, MAX_COMMAND_IDS)",
        "parseStrings(fieldValues, MAX_EVENT_FIELDS)",
        "value instanceof String",
        "String.valueOf(value)",
        "stringValue(JSONObject json, String key, String fallback)",
    ]:
        assert token in report
    for token in [
        "MAX_CACHE_CHARS = XbsCompatibilityReport.MAX_RAW_CHARS",
        "Compatibility cache exceeded bounded evidence limit",
        "Compatibility evidence exceeded bounded cache limit",
    ]:
        assert token in store


def test_phase24_debug_health_reads_only_bounded_passive_caches():
    expected = {
        "DebugHealthStore.java": ["MAX_CACHE_CHARS = 32 * 1024", "raw.length() > MAX_CACHE_CHARS"],
        "DebugHealthReport.java": ["MAX_SUPPORT_TEXT_CHARS = 16 * 1024", "items.length() > MAX_ITEMS"],
        "XbsStateStore.java": ["MAX_SNAPSHOT_CHARS = 128 * 1024", "Stored state exceeds debug cache bounds"],
        "ProcessRepository.java": ["MAX_CACHE_CHARS = 512 * 1024", "raw.length() > MAX_CACHE_CHARS"],
        "XbsHistoryStore.java": ["MAX_STATUS_CACHE_CHARS = 16 * 1024", "raw.length() > MAX_STATUS_CACHE_CHARS"],
        "EventBridgeStore.java": ["MAX_CACHE_CHARS = 16 * 1024", "raw.length() > MAX_CACHE_CHARS"],
        "DebugHealthPolicy.java": ["MAX_EVIDENCE_TEXT_CHARS = 512", "value.startsWith(\"invalid\")"],
    }
    for name, tokens in expected.items():
        text = source(name)
        for token in tokens:
            assert token in text, f"{name} missing {token!r}"


def test_debug_health_evaluator_remains_transitively_passive():
    evaluator = source("DebugHealthEvaluator.java")
    for token in [
        "RootDiagnosticsStore.cached", "XbsCompatibilityStore.cached", "XbsHistoryStore.peekStatus",
        "XbsConfigTransactionStore.peekSupportText", "XbsConfigTransactionStore.peekCapturedAt",
        "ProcessRepository.cached", "EventBridgeStore.cached", "RunStore.peekActiveCount",
    ]:
        assert token in evaluator
    for forbidden in [
        "XbsRootApi.checkRoot", "RootDiagnosticsProbe.run", "ProcessRepository.load",
        "XbsCompatibilityProbe.collect", "RootCommandRunner", "runRoot", "latestTransactionSupport",
    ]:
        assert forbidden not in evaluator
