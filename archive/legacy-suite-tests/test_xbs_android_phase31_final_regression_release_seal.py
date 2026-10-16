from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import zipfile

import pytest

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "apps/xbs/android-app"


def load_gate():
    path = ANDROID / "tools/phase31_release_gate.py"
    spec = importlib.util.spec_from_file_location("phase31_release_gate", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader
    spec.loader.exec_module(module)
    return module


def fresh_session(gate, directory: Path) -> tuple[dict, bytes]:
    now = gate.now_ms()
    session = {
        "format": gate.FORMAT_VERSION,
        "kind": "phase31_session",
        "session_id": "a" * 64,
        "started_at": now - 1_000,
        "session_dir": str(directory),
        "app_version_name": gate.APP_VERSION_NAME,
        "app_version_code": gate.APP_VERSION_CODE,
        "repository_tree_sha256": "b" * 64,
        "promise_seal_sha256": "c" * 64,
    }
    return session, b"secret" * 8


def pass_gate(gate, detail: str = "verified") -> dict:
    return gate.pass_result(detail)


def complete_host(gate, session: dict, secret: bytes, profile: bytes, metadata: bytes) -> dict:
    gates = {name: pass_gate(gate) for name in gate.HOST_GATES}
    gates["generated_baseline_profile"].update({
        "candidate_packaged_profile_sha256": gate.sha256_bytes(profile),
        "candidate_packaged_profile_metadata_sha256": gate.sha256_bytes(metadata),
        "sample_descriptors": ["Lio/github/mikeceead/xbscompanion/MainActivity;"],
    })
    return gate.bind_evidence({
        "status": "PASS",
        "detail": "all host gates passed",
        "captured_at": gate.now_ms(),
        "source_seal_sha256": session["repository_tree_sha256"],
        "promise_seal_sha256": session["promise_seal_sha256"],
        "candidate_receipt_sha256": "d" * 64,
        "gates": gates,
        "blocked": [],
    }, "host_evidence", session, secret)


def complete_apk_evidence(gate, session: dict, secret: bytes, host: dict,
                          profile: bytes, metadata: bytes) -> dict:
    cert = "ab" * 32
    return gate.bind_evidence({
        "status": "PASS",
        "detail": "exact APK checks passed",
        "captured_at": max(gate.now_ms(), int(host["captured_at"])),
        "apk_sha256": "e" * 64,
        "source_seal_sha256": session["repository_tree_sha256"],
        "host_evidence_sha256": gate.sha256_bytes(gate.canonical_json_bytes(host)),
        "host_evidence_asset_sha256": gate.sha256_bytes(gate.asset_bytes(host)),
        "final_receipt_sha256": "f" * 64,
        "certificate_sha256": cert,
        "certificate_set": [cert],
        "profile_sha256": gate.sha256_bytes(profile),
        "profile_metadata_sha256": gate.sha256_bytes(metadata),
        "gates": {
            "signed_apk": pass_gate(gate, "one exact signer"),
            "packaged_baseline_profile": pass_gate(gate, "strict profgen parse"),
        },
    }, "apk_evidence", session, secret)


def final_receipt(gate, root: Path, session: dict, secret: bytes, host: dict,
                  asset: Path, apk: Path) -> dict:
    stat = apk.stat()
    record = {
        "path": apk.relative_to(root).as_posix(),
        "sha256": gate.sha256_file(apk),
        "size": stat.st_size,
        "mtime_ms": int(stat.st_mtime * 1000),
        "producer_tasks": [":app:assembleRelease"],
    }
    return gate.bind_evidence({
        "status": "PASS",
        "detail": "final rerun receipt",
        "captured_at": max(gate.now_ms(), int(host["captured_at"])),
        "source_seal_sha256": session["repository_tree_sha256"],
        "promise_seal_sha256": session["promise_seal_sha256"],
        "final_git_head": "1" * 40,
        "host_evidence_sha256": gate.sha256_bytes(gate.canonical_json_bytes(host)),
        "host_evidence_asset_sha256": gate.sha256_file(asset),
        "outputs": {"final_apk": [record]},
    }, "final_receipt", session, secret)


def test_phase31_source_promise_audit_executes_complete_tree_implementation():
    gate = load_gate()
    result = gate.source_audit(ANDROID)
    assert result["status"] == "PASS", result
    assert len(result["source_seal_sha256"]) == 64
    assert result["repository_tree_file_count"] >= 800


def test_phase31_declares_exact_fail_closed_fourteen_gate_contract():
    contract = json.loads((ANDROID / "PHASE31_PROMISE_CONTRACT.json").read_text())
    gate = load_gate()
    assert contract["format"] == 2
    assert contract["revision"] == "r3"
    assert contract["fail_closed"] is True
    assert contract["expected_python_contracts"] == gate.EXPECTED_PYTHON_CONTRACTS == 237
    assert len(contract["gate_ids"]) == len(set(contract["gate_ids"])) == 14
    assert tuple(contract["gate_ids"]) == gate.GATES
    assert "profgen" in contract["truth_contract"]
    assert "profman" not in contract["truth_contract"]


def test_complete_source_manifest_rejects_extra_source_file(tmp_path):
    gate = load_gate()
    repo = tmp_path / "repo"
    android = repo / "apps/xbs/android-app"
    (android / "app/src/main/java/example").mkdir(parents=True)
    (repo / "tests").mkdir(parents=True)
    (android / "app/src/main/java/example/App.java").write_text("class App {}\n")
    manifest = gate.build_expected_tree_manifest(android)
    gate.write_json(android / gate.SOURCE_TREE_MANIFEST, manifest)
    assert gate.verify_expected_tree(android)["repository_tree_sha256"] == manifest["repository_tree_sha256"]
    (android / "app/src/main/java/example/Unexpected.java").write_text("class Unexpected {}\n")
    with pytest.raises(gate.GateError, match="extra=.*Unexpected.java"):
        gate.verify_expected_tree(android)


def test_synthetic_reports_without_signed_gradle_receipt_are_blocked(tmp_path):
    gate = load_gate()
    session, secret = fresh_session(gate, tmp_path)
    receipt = tmp_path / "candidate-receipt.json"
    receipt.write_text(json.dumps({
        "format": 2,
        "kind": "candidate_receipt",
        "session_id": session["session_id"],
        "status": "PASS",
        "gates": {name: {"status": "PASS"} for name in gate.HOST_GATES},
        "signature_hmac_sha256": "0" * 64,
    }))
    with pytest.raises(gate.GateError, match="HMAC"):
        gate.collect_evidence(tmp_path, session, secret, receipt)


def test_hmac_receipt_tampering_is_rejected(tmp_path):
    gate = load_gate()
    session, secret = fresh_session(gate, tmp_path)
    payload = gate.bind_evidence({"status": "PASS", "detail": "real", "captured_at": gate.now_ms()},
                                 "candidate_receipt", session, secret)
    gate.verify_bound_evidence(payload, "candidate_receipt", session, secret)
    payload["detail"] = "forged"
    with pytest.raises(gate.GateError, match="HMAC"):
        gate.verify_bound_evidence(payload, "candidate_receipt", session, secret)


def test_finalizer_rejects_forged_blocker_envelopes_even_with_nested_passes(tmp_path):
    gate = load_gate()
    session, secret = fresh_session(gate, tmp_path)
    profile = b"pro\x00" + b"A" * 64
    metadata = b"prm\x00" + b"B" * 32
    host = complete_host(gate, session, secret, profile, metadata)
    apk = complete_apk_evidence(gate, session, secret, host, profile, metadata)
    host["status"] = "BLOCKER"
    host = gate.sign_payload(host, secret)
    apk["status"] = "BLOCKER"
    apk = gate.sign_payload(apk, secret)
    with pytest.raises(gate.GateError, match="top-level"):
        gate.finalize(host, apk, session, secret)


def test_finalizer_rejects_extra_gate_and_broken_hash_binding(tmp_path):
    gate = load_gate()
    session, secret = fresh_session(gate, tmp_path)
    profile = b"pro\x00" + b"A" * 64
    metadata = b"prm\x00" + b"B" * 32
    host = complete_host(gate, session, secret, profile, metadata)
    host["gates"]["forged_extra"] = pass_gate(gate)
    host = gate.sign_payload(host, secret)
    apk = complete_apk_evidence(gate, session, secret, host, profile, metadata)
    with pytest.raises(gate.GateError, match="gate set mismatch"):
        gate.finalize(host, apk, session, secret)
    clean_host = complete_host(gate, session, secret, profile, metadata)
    apk = complete_apk_evidence(gate, session, secret, clean_host, profile, metadata)
    apk["profile_sha256"] = "0" * 64
    apk = gate.sign_payload(apk, secret)
    with pytest.raises(gate.GateError, match="profile hash"):
        gate.finalize(clean_host, apk, session, secret)


def test_phase31_policy_is_pure_java_and_rejects_stale_or_incomplete_bindings(tmp_path):
    package = ANDROID / "app/src/main/java/io/github/mikeceead/xbscompanion"
    classes = tmp_path / "classes"
    classes.mkdir()
    harness = tmp_path / "FinalSealHarness.java"
    harness.write_text('''package io.github.mikeceead.xbscompanion;
public final class FinalSealHarness {
  private static FinalReleaseSealPolicy.Evidence ready(long now) {
    FinalReleaseSealPolicy.Evidence e = new FinalReleaseSealPolicy.Evidence();
    e.evaluatedAt=now; e.sessionStartedAt=now-2000; e.capturedAt=now-1000;
    e.sessionId="a".repeat(64); e.versionName="4.2.0"; e.versionCode=15;
    e.evidenceVersionName="4.2.0"; e.evidenceVersionCode=15;
    e.sourceSealSha256="b".repeat(64); e.promiseSealSha256="c".repeat(64);
    e.candidateReceiptSha256="d".repeat(64); e.hostEvidenceSha256="e".repeat(64);
    e.expectedPackagedProfileSha256="f".repeat(64); e.expectedPackagedProfileMetadataSha256="1".repeat(64);
    for(String id:FinalReleaseSealPolicy.hostGateIds()) e.hostGates.put(id,new FinalReleaseSealPolicy.HostGate(true,"real",now-1000));
    e.signedApkReady=true; e.signedApkDetail="signer"; e.signedApkCapturedAt=now;
    e.packagedBaselineProfileReady=true; e.packagedBaselineProfileDetail="profile"; e.packagedBaselineProfileCapturedAt=now;
    return e;
  }
  public static void main(String[] args) {
    long now=1800000000000L;
    if(!FinalReleaseSealPolicy.evaluate(ready(now)).ready()) throw new AssertionError("fresh evidence blocked");
    FinalReleaseSealPolicy.Evidence stale=ready(now); stale.sessionStartedAt=1; stale.capturedAt=2;
    if(FinalReleaseSealPolicy.evaluate(stale).ready()) throw new AssertionError("stale evidence passed");
    FinalReleaseSealPolicy.Evidence extra=ready(now); extra.hostGates.put("extra",new FinalReleaseSealPolicy.HostGate(true,"forged",now));
    if(FinalReleaseSealPolicy.evaluate(extra).ready()) throw new AssertionError("extra gate passed");
    FinalReleaseSealPolicy.Evidence missing=ready(now); missing.hostEvidenceSha256="";
    if(FinalReleaseSealPolicy.evaluate(missing).ready()) throw new AssertionError("missing hash binding passed");
  }
}''')
    subprocess.run(["javac", "-Xlint:all", "-Werror", "-d", str(classes),
                    str(package / "FinalReleaseSealReport.java"),
                    str(package / "FinalReleaseSealPolicy.java"), str(harness)], check=True)
    subprocess.run(["java", "-cp", str(classes),
                    "io.github.mikeceead.xbscompanion.FinalSealHarness"], check=True)


def test_arbitrary_packaged_profile_or_metadata_bytes_are_rejected(tmp_path):
    gate = load_gate()
    apk = tmp_path / "bad.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("assets/dexopt/baseline.prof", b"profile")
        archive.writestr("assets/dexopt/baseline.profm", b"metadata")
    with pytest.raises(gate.GateError, match="size invalid|ART profile magic"):
        gate.profile_zip_info(apk)
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("assets/dexopt/baseline.prof", b"pro\x00" + b"A" * 64)
        archive.writestr("assets/dexopt/baseline.profm", b"not-metadata")
    with pytest.raises(gate.GateError, match="metadata"):
        gate.profile_zip_info(apk)


def test_apk_verifier_enforces_final_receipt_exact_signer_set_and_profgen(tmp_path):
    gate = load_gate()
    root = tmp_path / "repo"
    root.mkdir()
    session, secret = fresh_session(gate, tmp_path)
    profile = b"pro\x00" + b"010\x00" + b"A" * 64
    metadata = b"prm\x00" + b"B" * 32
    host = complete_host(gate, session, secret, profile, metadata)
    asset = root / "phase31_release_evidence.properties"
    asset.write_bytes(gate.asset_bytes(host))
    apk = root / "app-release.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("assets/phase31_release_evidence.properties", asset.read_bytes())
        archive.writestr("assets/dexopt/baseline.prof", profile)
        archive.writestr("assets/dexopt/baseline.profm", metadata)
    os.utime(apk, None)
    receipt = final_receipt(gate, root, session, secret, host, asset, apk)
    fingerprint = "ab" * 32
    signer = tmp_path / "apksigner"
    signer.write_text("#!/bin/sh\nprintf '%s\\n' 'Verified using v2 scheme (APK Signature Scheme v2): true' 'Signer #1 certificate SHA-256 digest: " + fingerprint + "'\n")
    signer.chmod(0o755)
    profgen = tmp_path / "profgen"
    profgen.write_text('''#!/usr/bin/env python3
import pathlib, sys
args=sys.argv[1:]
out=pathlib.Path(args[args.index("--output")+1])
out.write_text("Lio/github/mikeceead/xbscompanion/MainActivity;\\n", encoding="utf-8")
''')
    profgen.chmod(0o755)
    result = gate.verify_apk(root, apk, str(signer), str(profgen), fingerprint, asset,
                             host, receipt, session, secret)
    assert result["status"] == "PASS"
    signer.write_text("#!/bin/sh\nprintf '%s\\n' 'Verified using v2 scheme (APK Signature Scheme v2): true' 'Signer #1 certificate SHA-256 digest: " + fingerprint + "' 'Signer #2 certificate SHA-256 digest: " + "cd" * 32 + "'\n")
    signer.chmod(0o755)
    with pytest.raises(gate.GateError, match="signer set mismatch"):
        gate.verify_apk(root, apk, str(signer), str(profgen), fingerprint, asset,
                        host, receipt, session, secret)


def test_runtime_certificate_profile_property_set_and_utf8_checks_are_exact():
    store = (ANDROID / "app/src/main/java/io/github/mikeceead/xbscompanion/FinalReleaseSealStore.java").read_text()
    assert "getApkContentsSigners()" in store
    assert "getSigningCertificateHistory()" not in store
    assert "signatures.length != 1" in store
    assert "expectedPropertyKeys()" in store
    assert "properties.stringPropertyNames().equals(expectedKeys)" in store
    assert "hasMagic(metadata, 'p', 'r', 'm')" in store
    assert "StandardCharsets.UTF_8" in store


def test_gradle_release_graph_declares_real_root_dependencies_ordering_and_python_contracts():
    root_build = (ANDROID / "build.gradle").read_text()
    app_build = (ANDROID / "app/build.gradle").read_text()
    script = (ANDROID / "gradle/phase31-release-seal.gradle").read_text()
    assert "id 'base'" in root_build
    assert "apply from: rootProject.file('gradle/phase31-release-seal.gradle')" in root_build
    assert "apply from: rootProject.file('gradle/phase31-release-seal.gradle')" not in app_build
    assert "if (project != rootProject)" in script
    assert "tasks = ['clean', 'phase31CandidateReceipt']" in script
    assert "':phase31PythonContracts'" in script
    assert "include 'test_xbs*.py'" in script
    assert "gradle.projectsEvaluated" in script
    assert "mustRunAfter project(':app').tasks.named('generateReleaseBaselineProfile')" in script
    assert "must run after :clean" in script
    assert "startParameter.rerunTasks = true" in script
    assert "--profgen" in script and "profman" not in script
    gate_tool = (ANDROID / "tools/phase31_release_gate.py").read_text()
    assert '"source tree changed before final receipt"' in gate_tool
    assert "final_git = git_finalization(root)" in gate_tool
    assert '"final receipt promise seal mismatch"' in gate_tool
    testkit = (ANDROID / "phase31-testkit/src/test/java/io/github/mikeceead/xbscompanion/Phase31ReleaseSealGradleTest.java").read_text()
    assert 'withArguments("phase31AssertGraph"' in testkit
    assert "PHASE31_GRAPH_OK" in testkit


def test_devtool_manifest_must_be_fail_closed_after_packaging():
    contract = (ANDROID / "PHASE31_FINAL_REGRESSION_RELEASE_SEAL.md").read_text()
    assert "sandbox validation never substitutes" in contract
    assert "HMAC-signed Gradle task/output receipt" in contract
    assert "real Gradle" in contract


def test_phase31_has_no_seeded_profile_or_generated_build_evidence():
    assert not (ANDROID / "app/src/main/baseline-prof.txt").exists()
    assert not (ANDROID / "app/src/main/startup-prof.txt").exists()
    assert not (ANDROID / "app/src/main/assets/phase31_release_evidence.properties").exists()


def test_phase31_baseline_profile_plugin_configuration_is_supported_by_agp9():
    root_gradle = (ANDROID / "build.gradle").read_text(encoding="utf-8")
    app_gradle = (ANDROID / "app/build.gradle").read_text(encoding="utf-8")
    benchmark_gradle = (ANDROID / "benchmark/build.gradle").read_text(encoding="utf-8")
    properties = (ANDROID / "gradle.properties").read_text(encoding="utf-8")
    assert "androidx.baselineprofile' version '1.4.1'" in root_gradle
    assert "1.5.0-beta01" not in root_gradle
    assert "benchmark-macro-junit4:1.4.1" in benchmark_gradle
    assert "android.newDsl=false" in properties
    assert "automaticGenerationDuringBuild true" in app_gradle
    assert "saveInSrc false" in app_gradle
    assert "automaticGenerationDuringBuild false" not in app_gradle
    assert "androidx.lifecycle:lifecycle-runtime-android:2.8.7" in app_gradle
    assert "androidx.lifecycle:lifecycle-viewmodel-android:2.8.7" in app_gradle
    assert "androidx.lifecycle:lifecycle-runtime:2.8.7" not in app_gradle
    assert "androidx.lifecycle:lifecycle-viewmodel:2.8.7" not in app_gradle

