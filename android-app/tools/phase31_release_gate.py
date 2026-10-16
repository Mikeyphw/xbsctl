#!/usr/bin/env python3
"""Fail-closed Phase 31 r3 release evidence, replay, APK, and source verifier.

The release graph uses a per-run random session and HMAC-signed receipts. Build reports
are accepted only when they were produced after the session began, are listed in the
actual Gradle task-output receipt, and still match the recorded SHA-256 values.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

GATES = (
    "jvm_tests", "release_lint", "signed_apk", "android_instrumentation",
    "room_migration", "accessibility", "merged_manifest", "macrobenchmark",
    "generated_baseline_profile", "packaged_baseline_profile", "gradle_testkit",
    "artifact_replay", "git_finalization", "source_promise_seal",
)
HOST_GATES = tuple(g for g in GATES if g not in {"signed_apk", "packaged_baseline_profile"})
PACKAGE = "io.github.mikeceead.xbscompanion"
APP_VERSION_NAME = "4.2.0"
APP_VERSION_CODE = 15
FORMAT_VERSION = 2
EXPECTED_PYTHON_CONTRACTS = 237
MAX_PROFILE_BYTES = 1_500_000
MAX_EVIDENCE_AGE_MS = 6 * 60 * 60 * 1000
MAX_FUTURE_SKEW_MS = 5 * 60 * 1000
OUTPUT_TIME_TOLERANCE_MS = 2_000
SOURCE_TREE_MANIFEST = "PHASE31_EXPECTED_SOURCE_TREE.json"
SESSION_ID_RE = re.compile(r"[0-9a-f]{64}")
HEX64_RE = re.compile(r"[0-9a-f]{64}")

REQUIRED_CANDIDATE_TASKS = (
    ":app:testReleaseUnitTest",
    ":app:lintRelease",
    ":app:assembleRelease",
    ":app:connectedReleaseAndroidTest",
    ":benchmark:connectedCheck",
    ":app:generateReleaseBaselineProfile",
    ":phase31-testkit:test",
    ":phase31PythonContracts",
)
REQUIRED_FINAL_TASKS = (":app:assembleRelease",)

TRANSIENT_DIRS = {
    ".git", ".gradle", ".idea", ".pytest_cache", "__pycache__", "build",
    ".devtool", ".devtool-artifact",
}
TRANSIENT_FILES = {".devtool-artifact.json", "local.properties"}


class GateError(RuntimeError):
    pass


def now_ms() -> int:
    return int(time.time() * 1000)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_bytes(payload: dict) -> bytes:
    return json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def read_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), f"JSON object required: {path}")
    return value


def pass_result(detail: str, captured_at: int | None = None, **extra) -> dict:
    return {"status": "PASS", "detail": detail, "captured_at": captured_at or now_ms(), **extra}


def block_result(detail: str, captured_at: int | None = None, **extra) -> dict:
    return {"status": "BLOCKER", "detail": detail, "captured_at": captured_at or now_ms(), **extra}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise GateError(message)


def normalize_hex(value: str) -> str:
    return re.sub(r"[^A-Fa-f0-9]", "", value or "").lower()


def repository_root(android_root: Path) -> Path:
    android_root = android_root.resolve()
    if len(android_root.parents) >= 3:
        candidate = android_root.parents[2]
        if (candidate / "apps/xbs/android-app").resolve() == android_root and (candidate / "tests").is_dir():
            return candidate
    return android_root


def is_transient(rel: Path) -> bool:
    if not rel.parts:
        return True
    if any(part in TRANSIENT_DIRS for part in rel.parts):
        return True
    if rel.name in TRANSIENT_FILES or rel.suffix in {".pyc", ".pyo"}:
        return True
    return False


def source_snapshot(repo: Path, include_manifest: bool = True) -> dict[str, dict]:
    repo = repository_root(repo)
    result: dict[str, dict] = {}
    for path in sorted(repo.rglob("*")):
        rel = path.relative_to(repo)
        if is_transient(rel):
            continue
        require(not path.is_symlink(), f"source symlink is forbidden: {rel}")
        if not path.is_file():
            continue
        if not include_manifest and rel.as_posix() == f"apps/xbs/android-app/{SOURCE_TREE_MANIFEST}":
            continue
        result[rel.as_posix()] = {"sha256": sha256_file(path), "size": path.stat().st_size}
    return result


def tree_digest(snapshot: dict[str, dict]) -> str:
    digest = hashlib.sha256()
    for rel, meta in sorted(snapshot.items()):
        path_bytes = rel.encode("utf-8")
        digest.update(len(path_bytes).to_bytes(4, "big"))
        digest.update(path_bytes)
        digest.update(bytes.fromhex(meta["sha256"]))
        digest.update(int(meta["size"]).to_bytes(8, "big"))
    return digest.hexdigest()


def expected_tree_manifest_path(android_root: Path) -> Path:
    return android_root / SOURCE_TREE_MANIFEST


def build_expected_tree_manifest(android_root: Path) -> dict:
    repo = repository_root(android_root)
    snapshot = source_snapshot(repo, include_manifest=False)
    return {
        "format": FORMAT_VERSION,
        "algorithm": "sha256-path-size-content-v1",
        "excluded": sorted(TRANSIENT_DIRS | TRANSIENT_FILES | {SOURCE_TREE_MANIFEST}),
        "file_count": len(snapshot),
        "repository_tree_sha256": tree_digest(snapshot),
        "files": [{"path": path, **meta} for path, meta in sorted(snapshot.items())],
    }


def verify_expected_tree(android_root: Path) -> dict:
    repo = repository_root(android_root)
    manifest_path = expected_tree_manifest_path(android_root)
    require(manifest_path.is_file(), f"{SOURCE_TREE_MANIFEST} is missing")
    manifest = read_json(manifest_path)
    require(manifest.get("format") == FORMAT_VERSION, "source-tree manifest format mismatch")
    entries = manifest.get("files")
    require(isinstance(entries, list) and entries, "source-tree manifest files are missing")
    declared: dict[str, dict] = {}
    for item in entries:
        require(isinstance(item, dict), "invalid source-tree manifest entry")
        rel = item.get("path", "")
        require(rel and rel not in declared, f"duplicate/empty source-tree path: {rel}")
        require(HEX64_RE.fullmatch(str(item.get("sha256", ""))) is not None, f"invalid source-tree hash: {rel}")
        declared[rel] = {"sha256": item["sha256"], "size": int(item.get("size", -1))}
    actual = source_snapshot(repo, include_manifest=False)
    missing = sorted(set(declared) - set(actual))
    extra = sorted(set(actual) - set(declared))
    changed = sorted(path for path in set(actual) & set(declared) if actual[path] != declared[path])
    declared_digest = tree_digest(declared)
    require(manifest.get("file_count") == len(declared), "source-tree manifest count mismatch")
    require(manifest.get("repository_tree_sha256") == declared_digest, "source-tree manifest digest is internally inconsistent")
    require(not missing and not extra and not changed,
            f"repository source tree mismatch missing={missing[:10]} extra={extra[:10]} changed={changed[:10]}")
    return {
        "repository_tree_sha256": declared_digest,
        "file_count": len(declared),
        "manifest_sha256": sha256_file(manifest_path),
    }


def source_contract_paths(root: Path) -> list[Path]:
    contract = root / "PHASE31_PROMISE_CONTRACT.json"
    require(contract.is_file(), "PHASE31_PROMISE_CONTRACT.json is missing")
    data = read_json(contract)
    paths = [root / item for item in data.get("source_seal_paths", [])]
    require(paths, "source_seal_paths is empty")
    for path in paths:
        require(path.is_file(), f"source-seal file missing: {path.relative_to(root)}")
    return paths


def compute_promise_seal(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(source_contract_paths(root), key=lambda p: p.relative_to(root).as_posix()):
        rel = path.relative_to(root).as_posix().encode("utf-8")
        data = path.read_bytes()
        digest.update(len(rel).to_bytes(4, "big"))
        digest.update(rel)
        digest.update(len(data).to_bytes(8, "big"))
        digest.update(data)
    return digest.hexdigest()


def load_secret(path: Path) -> bytes:
    require(path.is_file(), f"session secret is missing: {path}")
    data = path.read_bytes()
    require(len(data) >= 32, "session secret is too short")
    return data


def unsigned_payload(payload: dict) -> dict:
    copy = dict(payload)
    copy.pop("signature_hmac_sha256", None)
    return copy


def sign_payload(payload: dict, secret: bytes) -> dict:
    signed = unsigned_payload(payload)
    signed["signature_hmac_sha256"] = hmac.new(secret, canonical_json_bytes(signed), hashlib.sha256).hexdigest()
    return signed


def verify_signature(payload: dict, secret: bytes) -> None:
    signature = payload.get("signature_hmac_sha256", "")
    require(HEX64_RE.fullmatch(str(signature)) is not None, "evidence signature is missing or invalid")
    expected = hmac.new(secret, canonical_json_bytes(unsigned_payload(payload)), hashlib.sha256).hexdigest()
    require(hmac.compare_digest(signature, expected), "evidence HMAC signature mismatch")


def validate_timestamp(value: int, session: dict, *, allow_before_end: bool = True) -> None:
    now = now_ms()
    started = int(session.get("started_at", 0))
    require(value > 0, "evidence timestamp is missing")
    require(value >= started - OUTPUT_TIME_TOLERANCE_MS, "evidence predates the release session")
    require(value <= now + MAX_FUTURE_SKEW_MS, "evidence timestamp is in the future")
    require(now - value <= MAX_EVIDENCE_AGE_MS, "evidence is stale")


def load_session(path: Path) -> dict:
    session = read_json(path)
    require(session.get("format") == FORMAT_VERSION and session.get("kind") == "phase31_session", "invalid release session")
    require(SESSION_ID_RE.fullmatch(str(session.get("session_id", ""))) is not None, "invalid release session id")
    require(session.get("app_version_name") == APP_VERSION_NAME, "release session versionName mismatch")
    require(session.get("app_version_code") == APP_VERSION_CODE, "release session versionCode mismatch")
    validate_timestamp(int(session.get("started_at", 0)), session)
    return session


def bind_evidence(payload: dict, kind: str, session: dict, secret: bytes) -> dict:
    payload = dict(payload)
    payload.update({
        "format": FORMAT_VERSION,
        "kind": kind,
        "session_id": session["session_id"],
        "session_started_at": session["started_at"],
        "app_version_name": APP_VERSION_NAME,
        "app_version_code": APP_VERSION_CODE,
    })
    return sign_payload(payload, secret)


def verify_bound_evidence(payload: dict, kind: str, session: dict, secret: bytes) -> None:
    verify_signature(payload, secret)
    require(payload.get("format") == FORMAT_VERSION, f"{kind} format mismatch")
    require(payload.get("kind") == kind, f"{kind} evidence kind mismatch")
    require(payload.get("session_id") == session["session_id"], f"{kind} session mismatch")
    require(payload.get("session_started_at") == session["started_at"], f"{kind} session start mismatch")
    require(payload.get("app_version_name") == APP_VERSION_NAME, f"{kind} versionName mismatch")
    require(payload.get("app_version_code") == APP_VERSION_CODE, f"{kind} versionCode mismatch")
    validate_timestamp(int(payload.get("captured_at", 0)), session)


def start_session(root: Path, session_path: Path, secret_path: Path) -> dict:
    tree = verify_expected_tree(root)
    if session_path.parent.exists():
        shutil.rmtree(session_path.parent)
    session_path.parent.mkdir(parents=True, exist_ok=True)
    started = now_ms()
    session = {
        "format": FORMAT_VERSION,
        "kind": "phase31_session",
        "session_id": secrets.token_hex(32),
        "started_at": started,
        "app_version_name": APP_VERSION_NAME,
        "app_version_code": APP_VERSION_CODE,
        "repository_tree_sha256": tree["repository_tree_sha256"],
        "promise_seal_sha256": compute_promise_seal(root),
    }
    write_json(session_path, session)
    secret_path.write_bytes(secrets.token_bytes(32))
    try:
        secret_path.chmod(0o600)
    except OSError:
        pass
    return session


def source_audit(root: Path) -> dict:
    root = root.resolve()
    problems: list[str] = []
    def check(condition: bool, message: str) -> None:
        if not condition:
            problems.append(message)
    files = {
        "settings": root / "settings.gradle",
        "root_gradle": root / "build.gradle",
        "app_gradle": root / "app/build.gradle",
        "manifest": root / "app/src/main/AndroidManifest.xml",
        "policy": root / f"app/src/main/java/{PACKAGE.replace('.', '/')}/FinalReleaseSealPolicy.java",
        "report": root / f"app/src/main/java/{PACKAGE.replace('.', '/')}/FinalReleaseSealReport.java",
        "store": root / f"app/src/main/java/{PACKAGE.replace('.', '/')}/FinalReleaseSealStore.java",
        "activity": root / f"app/src/main/java/{PACKAGE.replace('.', '/')}/FinalReleaseSealActivity.java",
        "migration": root / f"app/src/androidTest/java/{PACKAGE.replace('.', '/')}/Phase31RoomMigrationInstrumentedTest.java",
        "accessibility": root / f"app/src/androidTest/java/{PACKAGE.replace('.', '/')}/Phase31AccessibilityInstrumentedTest.java",
        "benchmark": root / "benchmark/src/main/java/io/github/mikeceead/xbscompanion/benchmark/StartupMacrobenchmark.kt",
        "profile": root / "benchmark/src/main/java/io/github/mikeceead/xbscompanion/benchmark/BaselineProfileGenerator.kt",
        "script": root / "gradle/phase31-release-seal.gradle",
        "tool": root / "tools/phase31_release_gate.py",
        "schema1": root / "app/schemas/io.github.mikeceead.xbscompanion.XbsHistoryDatabase/1.json",
        "schema2": root / "app/schemas/io.github.mikeceead.xbscompanion.XbsHistoryDatabase/2.json",
        "tree": root / SOURCE_TREE_MANIFEST,
        "phase31_test": repository_root(root) / "tests/test_xbs_android_phase31_final_regression_release_seal.py",
    }
    for label, path in files.items():
        check(path.is_file(), f"missing {label}: {path.relative_to(repository_root(root))}")
    if problems:
        return block_result("; ".join(problems), problems=problems)
    contract = read_json(root / "PHASE31_PROMISE_CONTRACT.json")
    check(contract.get("format") == FORMAT_VERSION, f"promise contract format must be {FORMAT_VERSION}")
    check(contract.get("phase") == 31, "promise contract phase must be 31")
    check(contract.get("app_version_name") == APP_VERSION_NAME, "promise contract versionName mismatch")
    check(contract.get("app_version_code") == APP_VERSION_CODE, "promise contract versionCode mismatch")
    check(contract.get("support_bundle_format") == 18, "promise contract support format mismatch")
    check(contract.get("gate_ids") == list(GATES), "promise contract gate order/set mismatch")
    check(contract.get("fail_closed") is True, "promise contract is not fail-closed")
    check(contract.get("expected_python_contracts") == EXPECTED_PYTHON_CONTRACTS, "exact Python contract count mismatch")
    settings = files["settings"].read_text(encoding="utf-8")
    root_gradle = files["root_gradle"].read_text(encoding="utf-8")
    app_gradle = files["app_gradle"].read_text(encoding="utf-8")
    manifest = files["manifest"].read_text(encoding="utf-8")
    policy = files["policy"].read_text(encoding="utf-8")
    store = files["store"].read_text(encoding="utf-8")
    release_script = files["script"].read_text(encoding="utf-8")
    support = (root / f"app/src/main/java/{PACKAGE.replace('.', '/')}/SupportBundleBuilder.java").read_text(encoding="utf-8")
    db = (root / f"app/src/main/java/{PACKAGE.replace('.', '/')}/XbsHistoryDatabase.java").read_text(encoding="utf-8")
    check("include ':app', ':benchmark', ':phase31-testkit'" in settings, "settings does not include all Phase 31 modules")
    check("id 'base'" in root_gradle, "root Base plugin/clean task missing")
    check("apply from: rootProject.file('gradle/phase31-release-seal.gradle')" in root_gradle, "release seal script is not applied by the root project")
    check("apply from: rootProject.file('gradle/phase31-release-seal.gradle')" not in app_gradle, "release seal script must not be applied by :app")
    check("com.android.application' version '9.3.1" in root_gradle, "AGP 9.3.1 pin missing")
    check("androidx.baselineprofile' version '1.4.1" in root_gradle, "stable Baseline Profile plugin 1.4.1 pin missing")
    benchmark_gradle = (root / 'benchmark/build.gradle').read_text(encoding='utf-8')
    gradle_props = (root / 'gradle.properties').read_text(encoding='utf-8')
    check("benchmark-macro-junit4:1.4.1" in benchmark_gradle, "stable Macrobenchmark 1.4.1 pin missing")
    check("buildToolsVersion '36.0.0'" in app_gradle and "buildToolsVersion '36.0.0'" in benchmark_gradle, "Build Tools 36.0.0 pin missing")
    check(app_gradle.count('JavaVersion.VERSION_21') >= 2 and benchmark_gradle.count('JavaVersion.VERSION_21') >= 2, "Java 21 source/bytecode pin missing")
    check(any(line.strip() == 'android.newDsl=false' for line in gradle_props.splitlines()), "stable Baseline Profile 1.4.1 requires android.newDsl=false on AGP 9.x")
    check("xbsReleaseVersionCode = 15" in app_gradle and "xbsReleaseVersionName = '4.2.0'" in app_gradle, "release version mismatch")
    check("testBuildType 'release'" in app_gradle, "release instrumentation target missing")
    check("warningsAsErrors true" in app_gradle and "checkReleaseBuilds true" in app_gradle, "release lint fail-closed settings missing")
    check("automaticGenerationDuringBuild true" in app_gradle and "saveInSrc false" in app_gradle, "Baseline Profile plugin-supported build-dir generation settings missing")
    check("FORMAT_VERSION = 18" in support and "[Final release seal]" in support, "support bundle v18/final seal missing")
    check("exportSchema = true" in db and "MIGRATION_1_2" in db, "Room exported schema or migration missing")
    check(policy.count("titles.put(") == 14, "policy does not declare exactly 14 gates")
    check("MAX_EVIDENCE_AGE_MS" in policy and "MAX_FUTURE_SKEW_MS" in policy, "runtime freshness policy missing")
    check("signatures.length != 1" in store, "runtime exact signer-set enforcement missing")
    check("properties.stringPropertyNames().equals(expectedKeys)" in store, "runtime exact bundled-property set enforcement missing")
    check("hasMagic(metadata, 'p', 'r', 'm')" in store, "runtime ART profile metadata validation missing")
    check("expectedPackagedProfileSha256" in store, "runtime profile hash binding missing")
    check("promiseSealSha256" in policy and "candidateReceiptSha256" in policy and "hostEvidenceSha256" in policy,
          "runtime complete host binding hashes missing")
    for gate in GATES:
        check(f'"{gate}"' in policy, f"gate missing from policy: {gate}")
    check('android:usesCleartextTraffic="false"' in manifest, "cleartext traffic is not disabled")
    check('android:allowBackup="false"' in manifest, "backup is not disabled")
    check("CompilationMode.Partial(BaselineProfileMode.Require)" in files["benchmark"].read_text(encoding="utf-8"), "macrobenchmark does not require generated profile")
    check("BaselineProfileRule" in files["profile"].read_text(encoding="utf-8"), "real BaselineProfileRule missing")
    check("MigrationTestHelper" in files["migration"].read_text(encoding="utf-8") and "runMigrationsAndValidate" in files["migration"].read_text(encoding="utf-8"), "real Room migration test missing")
    check("AccessibilityChecks.enable()" in files["accessibility"].read_text(encoding="utf-8"), "Espresso Accessibility checks missing")
    check("phase31CandidateReceipt" in release_script and "phase31FinalReceipt" in release_script and "phase31AssertGraph" in release_script and "phase31PythonContracts" in release_script, "executable release graph/receipts missing")
    check("record-candidate" in release_script and "record-final" in release_script and "start-session" in release_script, "session-bound provenance missing")
    check("startParameter.rerunTasks = true" in release_script, "final assembly is not forced to rerun")
    check("mustRunAfter project(':app').tasks.named('generateReleaseBaselineProfile')" in release_script,
          "candidate release assembly is not ordered after Baseline Profile generation")
    check("--profgen" in release_script and "profman" not in release_script,
          "strict profgen APK profile verification is not wired")
    forbidden = [root / "app/src/main/baseline-prof.txt", root / "app/src/main/startup-prof.txt"]
    for path in forbidden:
        check(not path.exists(), f"hand-authored profile forbidden: {path.relative_to(root)}")
    for schema_path in (files["schema1"], files["schema2"]):
        try:
            schema = read_json(schema_path)
            check(schema.get("database", {}).get("version") in (1, 2), f"invalid Room schema: {schema_path.name}")
        except Exception as error:
            problems.append(f"unreadable Room schema {schema_path.name}: {error}")
    tree: dict = {}
    try:
        tree = verify_expected_tree(root)
    except Exception as error:
        problems.append(str(error))
    try:
        promise = compute_promise_seal(root)
    except Exception as error:
        problems.append(str(error))
        promise = ""
    status = "PASS" if not problems else "BLOCKER"
    return {
        "status": status,
        "detail": "Phase 31 r3 source, exact repository tree, and promise contract verified" if not problems else "; ".join(problems),
        "captured_at": now_ms(),
        "source_seal_sha256": tree.get("repository_tree_sha256", ""),
        "repository_tree_file_count": tree.get("file_count", 0),
        "source_tree_manifest_sha256": tree.get("manifest_sha256", ""),
        "promise_seal_sha256": promise,
        "problems": problems,
    }


def discover_xml(root: Path, patterns: list[str]) -> list[Path]:
    found: list[Path] = []
    for pattern in patterns:
        found.extend(root.glob(pattern))
    return sorted({p.resolve() for p in found if p.is_file()})


def junit_gate(paths: list[Path], required_names: tuple[str, ...] = (), minimum_tests: int = 1,
               expected_tests: int | None = None, allow_skipped: bool = False) -> dict:
    if not paths:
        return block_result("JUnit XML evidence is missing")
    tests = failures = errors = skipped = 0
    names: set[str] = set()
    parse_errors: list[str] = []
    for path in paths:
        try:
            tree = ET.parse(path)
            xml_root = tree.getroot()
            suites = [xml_root] if xml_root.tag == "testsuite" else list(xml_root.findall(".//testsuite"))
            for suite in suites:
                tests += int(float(suite.attrib.get("tests", "0") or 0))
                failures += int(float(suite.attrib.get("failures", "0") or 0))
                errors += int(float(suite.attrib.get("errors", "0") or 0))
                skipped += int(float(suite.attrib.get("skipped", "0") or 0))
            for case in xml_root.findall(".//testcase"):
                names.add((case.attrib.get("classname", "") + "." + case.attrib.get("name", "")).strip("."))
        except Exception as error:
            parse_errors.append(f"{path}: {error}")
    missing = [name for name in required_names if not any(name in candidate for candidate in names)]
    exact_mismatch = expected_tests is not None and tests != expected_tests
    skipped_blocker = skipped > 0 and not allow_skipped
    if parse_errors or tests < minimum_tests or exact_mismatch or failures or errors or skipped_blocker or missing:
        return block_result(
            f"tests={tests} minimum={minimum_tests} expected={expected_tests} failures={failures} errors={errors} skipped={skipped} missing={missing} parse_errors={len(parse_errors)}",
            tests=tests, minimum_tests=minimum_tests, expected_tests=expected_tests, failures=failures, errors=errors, skipped=skipped, missing=missing,
        )
    return pass_result(f"tests={tests} failures=0 errors=0 skipped=0", tests=tests, skipped=skipped)


def lint_gate(paths: list[Path]) -> dict:
    if not paths:
        return block_result("release lint XML is missing")
    issues: list[tuple[str, str]] = []
    try:
        for path in paths:
            for issue in ET.parse(path).getroot().findall(".//issue"):
                severity = issue.attrib.get("severity", "")
                if severity in {"Fatal", "Error", "Warning"}:
                    issues.append((severity, issue.attrib.get("id", "unknown")))
    except Exception as error:
        return block_result(f"unable to parse release lint: {error}")
    if issues:
        return block_result(f"release lint contains {len(issues)} fatal/error/warning issue(s)", issues=issues[:50])
    return pass_result("release lint contains zero fatal, error, or warning issues")


def manifest_gate(paths: list[Path]) -> dict:
    if not paths:
        return block_result("merged release manifest is missing")
    android = "{http://schemas.android.com/apk/res/android}"
    try:
        path = paths[-1]
        manifest = ET.parse(path).getroot()
        app = manifest.find("application")
        require(app is not None, "merged manifest has no application")
        require(app.attrib.get(android + "usesCleartextTraffic") == "false", "usesCleartextTraffic is not false")
        require(app.attrib.get(android + "allowBackup") == "false", "allowBackup is not false")
        manifest_package = manifest.attrib.get("package", PACKAGE) or PACKAGE
        def component_name(raw: str) -> str:
            if raw.startswith("."):
                return manifest_package + raw
            if "." not in raw:
                return manifest_package + "." + raw
            return raw
        exported: list[tuple[str, str]] = []
        for kind in ("activity", "receiver", "service", "provider"):
            for node in app.findall(kind):
                if node.attrib.get(android + "exported") == "true":
                    exported.append((kind, component_name(node.attrib.get(android + "name", ""))))
        expected = {
            ("activity", PACKAGE + ".MainActivity"),
            ("activity", PACKAGE + ".TaskerPluginActivity"),
            ("activity", PACKAGE + ".TaskerEventEditActivity"),
            ("activity", PACKAGE + ".TaskerStateEditActivity"),
            ("receiver", PACKAGE + ".TaskerPluginReceiver"),
            ("receiver", PACKAGE + ".TaskerConditionReceiver"),
        }
        actual = set(exported)
        require(actual == expected, f"unexpected exported component set: {sorted(actual)}")
        final = [node for node in app.findall("activity") if component_name(node.attrib.get(android + "name", "")) == PACKAGE + ".FinalReleaseSealActivity"]
        require(len(final) == 1 and final[0].attrib.get(android + "exported") == "false", "FinalReleaseSealActivity is not private")
    except Exception as error:
        return block_result(f"merged-manifest security failed: {error}")
    return pass_result("merged release manifest has the exact private/exported security contract", exported=sorted(list(actual)))


def profile_text_gate(paths: list[Path]) -> dict:
    best: Path | None = None
    rules: list[str] = []
    for path in paths:
        current = [line.strip() for line in path.read_text(encoding="utf-8", errors="replace").splitlines() if line.strip() and not line.lstrip().startswith("#")]
        if len(current) > len(rules):
            best, rules = path, current
    if not best or not rules:
        return block_result("generated Baseline Profile with non-comment rules is missing")
    package_rules = [line for line in rules if "io/github/mikeceead/xbscompanion" in line]
    if not package_rules:
        return block_result("generated profile has no XBS Companion rules", path=str(best), rules=len(rules))
    descriptors: list[str] = []
    for line in package_rules:
        match = re.search(r"Lio/github/mikeceead/xbscompanion/[^;]+;", line)
        if match and match.group(0) not in descriptors:
            descriptors.append(match.group(0))
    return pass_result(
        f"generated Baseline Profile has {len(rules)} rule(s)",
        path=str(best), rules=len(rules), sha256=sha256_file(best), sample_descriptors=descriptors[:20],
    )


def macrobenchmark_gate(xml_paths: list[Path], json_paths: list[Path]) -> dict:
    junit = junit_gate(xml_paths, ("StartupMacrobenchmark",))
    if junit["status"] != "PASS":
        return block_result("macrobenchmark instrumentation did not pass: " + junit["detail"])
    interesting: list[Path] = []
    for path in json_paths:
        text = path.read_text(encoding="utf-8", errors="replace")
        if "StartupTimingMetric" in text or "startupMs" in text or "timeToInitialDisplayMs" in text:
            interesting.append(path)
    if not interesting:
        return block_result("macrobenchmark JSON metrics are missing")
    return pass_result(f"macrobenchmark passed with {len(interesting)} JSON metric report(s)", reports=[str(p) for p in interesting])


def profile_zip_info(apk: Path) -> dict:
    require(apk.is_file() and zipfile.is_zipfile(apk), f"APK missing or invalid: {apk}")
    with zipfile.ZipFile(apk) as archive:
        try:
            profile = archive.read("assets/dexopt/baseline.prof")
        except KeyError as error:
            raise GateError("APK has no assets/dexopt/baseline.prof") from error
        try:
            metadata = archive.read("assets/dexopt/baseline.profm")
        except KeyError as error:
            raise GateError("APK has no assets/dexopt/baseline.profm") from error
    require(32 <= len(profile) <= MAX_PROFILE_BYTES, f"packaged profile size invalid: {len(profile)}")
    require(profile.startswith(b"pro\x00"), "packaged Baseline Profile has no ART profile magic")
    require(8 <= len(metadata) <= MAX_PROFILE_BYTES, f"packaged profile metadata size invalid: {len(metadata)}")
    require(metadata.startswith(b"prm\x00"), "packaged Baseline Profile metadata has no ART metadata magic")
    return {
        "profile_bytes": len(profile),
        "profile_sha256": sha256_bytes(profile),
        "profile_metadata_bytes": len(metadata),
        "profile_metadata_sha256": sha256_bytes(metadata),
    }


def output_record(path: Path, repo: Path, producers: list[str], started_at: int) -> dict:
    require(path.is_file() and not path.is_symlink(), f"output is missing, non-regular, or a symlink: {path}")
    resolved = path.resolve()
    try:
        relative = resolved.relative_to(repo.resolve()).as_posix()
    except ValueError as error:
        raise GateError(f"output is outside the repository: {path}") from error
    stat = resolved.stat()
    mtime_ms = int(stat.st_mtime * 1000)
    require(mtime_ms >= started_at - OUTPUT_TIME_TOLERANCE_MS, f"output predates release session: {path}")
    return {
        "path": relative,
        "sha256": sha256_file(path),
        "size": stat.st_size,
        "mtime_ms": mtime_ms,
        "producer_tasks": sorted(set(producers)),
    }


def path_is_under(path: Path, output: Path) -> bool:
    try:
        if output.is_dir():
            path.resolve().relative_to(output.resolve())
            return True
        return path.resolve() == output.resolve()
    except (ValueError, OSError):
        return False


def producer_tasks(path: Path, graph_tasks: dict[str, dict]) -> list[str]:
    producers: list[str] = []
    for task_path, task in graph_tasks.items():
        if task.get("failure") or task.get("skipped") or task.get("no_source"):
            continue
        for raw in task.get("outputs", []):
            output = Path(raw)
            if output.exists() and path_is_under(path, output):
                producers.append(task_path)
                break
    return producers


def parse_gradle_version(value: str) -> tuple[int, int, int]:
    match = re.match(r"^(\d+)\.(\d+)(?:\.(\d+))?", value or "")
    require(match is not None, f"invalid Gradle version: {value}")
    return int(match.group(1)), int(match.group(2)), int(match.group(3) or 0)


def task_graph_from_input(path: Path, required_tasks: tuple[str, ...], required_requested: tuple[str, ...],
                          *, require_rerun: bool = False) -> dict[str, dict]:
    raw = read_json(path)
    gradle_version = str(raw.get("gradle_version", ""))
    parsed_gradle = parse_gradle_version(gradle_version)
    require(parsed_gradle == (9, 7, 1),
            f"Phase 31 requires fleet Gradle 9.7.1; found {gradle_version}")
    tasks = raw.get("tasks")
    require(isinstance(tasks, list) and tasks, "Gradle task receipt contains no tasks")
    result: dict[str, dict] = {}
    for item in tasks:
        require(isinstance(item, dict) and item.get("path"), "invalid Gradle task receipt entry")
        task_path = str(item["path"])
        require(task_path not in result, f"duplicate Gradle task receipt entry: {task_path}")
        result[task_path] = item
    for required in required_tasks:
        require(required in result, f"required task was not in the executed graph: {required}")
        state = result[required]
        require(not state.get("failure"), f"task failed: {required}")
        require(not state.get("skipped"), f"task was skipped: {required}")
        require(not state.get("no_source"), f"task had no source: {required}")
        if require_rerun:
            require(not state.get("up_to_date"), f"final task was unexpectedly up-to-date: {required}")
    requested = raw.get("requested_tasks", [])
    require(isinstance(requested, list), "Gradle requested_tasks must be a list")
    for name in required_requested:
        require(name in requested, f"Gradle invocation did not request: {name}")
    if require_rerun:
        require(raw.get("rerun_tasks") is True, "final Gradle invocation did not use --rerun-tasks")
    return result


def record_candidate(root: Path, session: dict, secret: bytes, graph_input: Path) -> dict:
    repo = repository_root(root)
    tree = verify_expected_tree(root)
    require(tree["repository_tree_sha256"] == session["repository_tree_sha256"],
            "source tree changed before candidate receipt")
    require(compute_promise_seal(root) == session["promise_seal_sha256"],
            "promise seal changed before candidate receipt")
    graph_tasks = task_graph_from_input(
        graph_input, REQUIRED_CANDIDATE_TASKS, ("clean", "phase31CandidateReceipt")
    )
    started = int(session["started_at"])
    groups: dict[str, list[Path]] = {
        "jvm_unit": discover_xml(root, ["app/build/test-results/testReleaseUnitTest/TEST-*.xml"]),
        "python_contracts": [session_path_for(session, "pytest-xbs.xml")],
        "lint": discover_xml(root, ["app/build/reports/lint-results-release.xml", "app/build/**/lint-results-release.xml"]),
        "instrumentation": discover_xml(root, [
            "app/build/outputs/androidTest-results/connected/**/*.xml",
            "app/build/outputs/androidTest-results/managedDevice/**/*.xml",
            "app/build/reports/androidTests/connected/**/*.xml",
        ]),
        "manifest": discover_xml(root, [
            "app/build/intermediates/merged_manifests/release/**/AndroidManifest.xml",
            "app/build/intermediates/packaged_manifests/release/**/AndroidManifest.xml",
        ]),
        "macrobenchmark_xml": discover_xml(root, [
            "benchmark/build/outputs/androidTest-results/connected/**/*.xml",
            "benchmark/build/outputs/androidTest-results/managedDevice/**/*.xml",
            "benchmark/build/reports/androidTests/connected/**/*.xml",
        ]),
        "macrobenchmark_json": sorted({p.resolve() for pattern in ["benchmark/build/outputs/**/*.json", "benchmark/build/reports/**/*.json"] for p in root.glob(pattern) if p.is_file()}),
        "generated_profile": sorted({p.resolve() for pattern in ["app/build/**/baseline-prof.txt", "app/build/**/baselineProfiles/**/*.txt", "app/build/outputs/baselineProfiles/**/*.txt"] for p in root.glob(pattern) if p.is_file()}),
        "testkit": discover_xml(root, ["phase31-testkit/build/test-results/test/TEST-*.xml"]),
        "candidate_apk": [root / "app/build/outputs/apk/release/app-release.apk"],
    }
    for label, paths in groups.items():
        require(paths and all(path.is_file() for path in paths), f"candidate output group is missing: {label}")
    outputs: dict[str, list[dict]] = {}
    for label, paths in groups.items():
        records: list[dict] = []
        for path in paths:
            producers = producer_tasks(path, graph_tasks)
            if label == "python_contracts":
                producers = [task for task in producers if task == ":phase31PythonContracts"]
            require(producers, f"no executed Gradle producer declared output containing: {path}")
            records.append(output_record(path, repo, producers, started))
        outputs[label] = records
    candidate_profile = profile_zip_info(groups["candidate_apk"][0])
    result = {
        "status": "PASS",
        "detail": "clean candidate tasks completed and all release outputs were captured with producer paths and SHA-256 values",
        "captured_at": now_ms(),
        "gradle_version": read_json(graph_input).get("gradle_version", ""),
        "required_tasks": list(REQUIRED_CANDIDATE_TASKS),
        "executed_task_count": len(graph_tasks),
        "graph_input_sha256": sha256_file(graph_input),
        "outputs": outputs,
        "candidate_packaged_profile": candidate_profile,
        "source_seal_sha256": session["repository_tree_sha256"],
        "promise_seal_sha256": session["promise_seal_sha256"],
    }
    return bind_evidence(result, "candidate_receipt", session, secret)


def record_final(root: Path, session: dict, secret: bytes, graph_input: Path, host_path: Path,
                 evidence_asset: Path, apk: Path) -> dict:
    repo = repository_root(root)
    tree = verify_expected_tree(root)
    require(tree["repository_tree_sha256"] == session["repository_tree_sha256"],
            "source tree changed before final receipt")
    require(compute_promise_seal(root) == session["promise_seal_sha256"],
            "promise seal changed before final receipt")
    final_git = git_finalization(root)
    require(final_git.get("status") == "PASS", "final Git state is not clean and exact: " + final_git.get("detail", "unknown"))
    graph_tasks = task_graph_from_input(
        graph_input, REQUIRED_FINAL_TASKS, ("phase31FinalReceipt",), require_rerun=True
    )
    host = read_json(host_path)
    verify_bound_evidence(host, "host_evidence", session, secret)
    require(host.get("status") == "PASS" and host.get("blocked") == [], "host evidence is not a complete PASS")
    require(host.get("source_seal_sha256") == session["repository_tree_sha256"], "host source seal does not match the session")
    require(evidence_asset.is_file() and evidence_asset.read_bytes() == asset_bytes(host),
            "final-build host evidence asset is missing or non-canonical")
    producers = producer_tasks(apk, graph_tasks)
    require(producers, "no executed final Gradle task declared the release APK as output")
    apk_record = output_record(apk, repo, producers, int(host["captured_at"]))
    result = {
        "status": "PASS",
        "detail": "rerun final assembly completed and the exact APK plus canonical host asset were captured",
        "captured_at": now_ms(),
        "gradle_version": read_json(graph_input).get("gradle_version", ""),
        "required_tasks": list(REQUIRED_FINAL_TASKS),
        "executed_task_count": len(graph_tasks),
        "graph_input_sha256": sha256_file(graph_input),
        "source_seal_sha256": session["repository_tree_sha256"],
        "promise_seal_sha256": session["promise_seal_sha256"],
        "final_git_head": final_git.get("head", ""),
        "host_evidence_sha256": sha256_bytes(canonical_json_bytes(host)),
        "host_evidence_asset_sha256": sha256_file(evidence_asset),
        "outputs": {"final_apk": [apk_record]},
    }
    return bind_evidence(result, "final_receipt", session, secret)


def session_path_for(session: dict, name: str) -> Path:
    base = session.get("session_dir", "")
    require(base, "release session directory is missing")
    return Path(base) / name


def verify_output_records(repo: Path, receipt: dict, group: str, session: dict) -> list[Path]:
    records = receipt.get("outputs", {}).get(group, [])
    require(isinstance(records, list) and records, f"candidate receipt output group missing: {group}")
    paths: list[Path] = []
    for item in records:
        require(isinstance(item, dict), f"invalid candidate output record: {group}")
        path = repo / item.get("path", "")
        require(path.is_file(), f"candidate output disappeared: {item.get('path')}")
        require(item.get("sha256") == sha256_file(path), f"candidate output hash changed: {item.get('path')}")
        require(item.get("size") == path.stat().st_size, f"candidate output size changed: {item.get('path')}")
        mtime_ms = int(path.stat().st_mtime * 1000)
        require(abs(mtime_ms - int(item.get("mtime_ms", -1))) <= OUTPUT_TIME_TOLERANCE_MS, f"candidate output timestamp changed: {item.get('path')}")
        validate_timestamp(mtime_ms, session)
        producers = item.get("producer_tasks", [])
        require(isinstance(producers, list) and producers, f"candidate output has no producer task: {item.get('path')}")
        paths.append(path)
    return paths


def load_pass_evidence(path: Path, label: str, kind: str, session: dict, secret: bytes) -> tuple[dict, dict]:
    require(path.is_file(), f"{label} evidence JSON is missing")
    data = read_json(path)
    verify_bound_evidence(data, kind, session, secret)
    require(data.get("status") == "PASS", f"{label} did not pass: {data.get('detail', 'unknown')}")
    require(data.get("source_seal_sha256") == session["repository_tree_sha256"], f"{label} source seal mismatch")
    gate = pass_result(data.get("detail", f"{label} passed"), captured_at=int(data["captured_at"]),
                       evidence_sha256=sha256_file(path))
    return data, gate


def collect_evidence(root: Path, session: dict, secret: bytes, receipt_path: Path) -> dict:
    repo = repository_root(root)
    receipt = read_json(receipt_path)
    verify_bound_evidence(receipt, "candidate_receipt", session, secret)
    require(receipt.get("status") == "PASS", "candidate receipt did not pass")
    require(receipt.get("source_seal_sha256") == session["repository_tree_sha256"], "candidate source seal mismatch")
    require(receipt.get("promise_seal_sha256") == session["promise_seal_sha256"], "candidate promise seal mismatch")
    require(receipt.get("required_tasks") == list(REQUIRED_CANDIDATE_TASKS), "candidate required-task list mismatch")
    current_tree = verify_expected_tree(root)
    require(current_tree["repository_tree_sha256"] == session["repository_tree_sha256"],
            "source tree changed after the release session started")
    require(compute_promise_seal(root) == session["promise_seal_sha256"],
            "promise seal changed after the release session started")
    jvm = junit_gate(verify_output_records(repo, receipt, "jvm_unit", session))
    python_contracts = junit_gate(
        verify_output_records(repo, receipt, "python_contracts", session),
        expected_tests=EXPECTED_PYTHON_CONTRACTS
    )
    if jvm["status"] == "PASS" and python_contracts["status"] == "PASS":
        jvm_gate = pass_result(
            f"Android JVM tests={jvm.get('tests', 0)}; XBS Python contracts={python_contracts.get('tests', 0)}",
            android_jvm_tests=jvm.get("tests", 0), python_contracts=python_contracts.get("tests", 0),
        )
    else:
        jvm_gate = block_result(f"Android JVM: {jvm['detail']}; Python contracts: {python_contracts['detail']}")
    profile_gate = profile_text_gate(verify_output_records(repo, receipt, "generated_profile", session))
    candidate_profile = receipt.get("candidate_packaged_profile", {})
    if profile_gate["status"] == "PASS":
        try:
            require(HEX64_RE.fullmatch(str(candidate_profile.get("profile_sha256", ""))) is not None, "candidate packaged profile hash missing")
            require(HEX64_RE.fullmatch(str(candidate_profile.get("profile_metadata_sha256", ""))) is not None, "candidate packaged profile metadata hash missing")
            profile_gate.update({
                "candidate_packaged_profile_sha256": candidate_profile["profile_sha256"],
                "candidate_packaged_profile_metadata_sha256": candidate_profile["profile_metadata_sha256"],
            })
        except Exception as error:
            profile_gate = block_result(str(error))
    source_data, source_gate = load_pass_evidence(
        session_path_for(session, "source-audit.json"), "source/promise audit", "source_audit", session, secret
    )
    replay_data, replay_gate = load_pass_evidence(
        session_path_for(session, "artifact-replay.json"), "artifact replay", "artifact_replay", session, secret
    )
    git_data, git_gate = load_pass_evidence(
        session_path_for(session, "git-finalization.json"), "Git finalization", "git_finalization", session, secret
    )
    require(source_data.get("promise_seal_sha256") == session["promise_seal_sha256"], "source audit promise seal mismatch")
    require(replay_data.get("source_seal_sha256") == source_data.get("source_seal_sha256"), "replay/source audit seal mismatch")
    require(git_data.get("source_seal_sha256") == source_data.get("source_seal_sha256"), "Git/source audit seal mismatch")
    gates = {
        "jvm_tests": jvm_gate,
        "release_lint": lint_gate(verify_output_records(repo, receipt, "lint", session)),
        "android_instrumentation": junit_gate(verify_output_records(repo, receipt, "instrumentation", session)),
        "room_migration": junit_gate(verify_output_records(repo, receipt, "instrumentation", session), ("Phase31RoomMigrationInstrumentedTest",)),
        "accessibility": junit_gate(verify_output_records(repo, receipt, "instrumentation", session), ("Phase31AccessibilityInstrumentedTest",)),
        "merged_manifest": manifest_gate(verify_output_records(repo, receipt, "manifest", session)),
        "macrobenchmark": macrobenchmark_gate(
            verify_output_records(repo, receipt, "macrobenchmark_xml", session),
            verify_output_records(repo, receipt, "macrobenchmark_json", session),
        ),
        "generated_baseline_profile": profile_gate,
        "gradle_testkit": junit_gate(verify_output_records(repo, receipt, "testkit", session), ("Phase31ReleaseSealGradleTest",)),
        "artifact_replay": replay_gate,
        "git_finalization": git_gate,
        "source_promise_seal": source_gate,
    }
    blocked = [name for name, value in gates.items() if value.get("status") != "PASS"]
    result = {
        "status": "PASS" if not blocked else "BLOCKER",
        "detail": "all session-bound host gates passed" if not blocked else "blocked host gates: " + ", ".join(blocked),
        "captured_at": now_ms(),
        "source_seal_sha256": source_data.get("source_seal_sha256", ""),
        "promise_seal_sha256": source_data.get("promise_seal_sha256", ""),
        "candidate_receipt_sha256": sha256_file(receipt_path),
        "gates": gates,
        "blocked": blocked,
    }
    return bind_evidence(result, "host_evidence", session, secret)


def escape_property(value: object) -> str:
    return str(value).replace("\\", "\\\\").replace("\n", " ").replace("\r", " ").replace("=", "\\=")


def asset_bytes(evidence: dict) -> bytes:
    profile = evidence.get("gates", {}).get("generated_baseline_profile", {})
    lines = [
        f"format={FORMAT_VERSION}",
        f"session_id={evidence.get('session_id', '')}",
        f"session_started_at={evidence.get('session_started_at', 0)}",
        f"captured_at={evidence.get('captured_at', 0)}",
        f"app_version_name={APP_VERSION_NAME}",
        f"app_version_code={APP_VERSION_CODE}",
        f"source_seal_sha256={evidence.get('source_seal_sha256', '')}",
        f"promise_seal_sha256={evidence.get('promise_seal_sha256', '')}",
        f"candidate_receipt_sha256={evidence.get('candidate_receipt_sha256', '')}",
        f"expected_packaged_profile_sha256={profile.get('candidate_packaged_profile_sha256', '')}",
        f"expected_packaged_profile_metadata_sha256={profile.get('candidate_packaged_profile_metadata_sha256', '')}",
        f"host_evidence_sha256={sha256_bytes(canonical_json_bytes(evidence))}",
    ]
    for gate in HOST_GATES:
        item = evidence.get("gates", {}).get(gate, block_result("missing"))
        lines.extend([
            f"gate.{gate}.status={item.get('status', 'BLOCKER')}",
            f"gate.{gate}.captured_at={item.get('captured_at', 0)}",
            f"gate.{gate}.detail={escape_property(item.get('detail', ''))}",
        ])
    return ("\n".join(lines) + "\n").encode("utf-8")


def write_asset(path: Path, evidence: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(asset_bytes(evidence))


def extract_baseline(source: Path, target: Path) -> Path:
    if source.is_dir():
        shutil.copytree(source, target, dirs_exist_ok=True)
        return target
    require(source.is_file(), f"baseline not found: {source}")
    require(tarfile.is_tarfile(source), "baseline must be a directory or tar archive")
    with tarfile.open(source) as archive:
        for member in archive.getmembers():
            resolved = (target / member.name).resolve()
            require(str(resolved).startswith(str(target.resolve()) + os.sep), "unsafe baseline tar path")
        archive.extractall(target, filter="data")
    children = [path for path in target.iterdir() if path.name != "__MACOSX"]
    return children[0] if len(children) == 1 and children[0].is_dir() else target


def artifact_replay(repo: Path, overlay: Path, baseline: Path) -> dict:
    require(overlay.is_file() and zipfile.is_zipfile(overlay), "overlay ZIP missing or invalid")
    with tempfile.TemporaryDirectory(prefix="phase31-replay-") as temporary:
        temporary_path = Path(temporary)
        stage = temporary_path / "overlay"
        stage.mkdir()
        base = temporary_path / "baseline"
        base.mkdir()
        with zipfile.ZipFile(overlay) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), "overlay ZIP contains duplicate names")
            for name in names:
                path = Path(name)
                require(not path.is_absolute() and ".." not in path.parts, f"unsafe overlay path: {name}")
                info = archive.getinfo(name)
                require((info.external_attr >> 16) & 0o170000 != 0o120000, f"overlay symlink forbidden: {name}")
            archive.extractall(stage)
        metadata = read_json(stage / ".devtool-artifact.json")
        require(metadata.get("schema_version") == 2, "overlay schema_version must be 2")
        require(metadata.get("type") == "overlay", "artifact type must be overlay")
        require(metadata.get("target") == "xbs_android", "artifact target must be xbs_android")
        require(metadata.get("workspace") == "apps/xbs/android-app", "artifact workspace mismatch")
        inventory_block = metadata.get("inventory", {})
        require(isinstance(inventory_block, dict) and inventory_block.get("format") == 1, "inventory format must be 1")
        inventory = inventory_block.get("entries", [])
        require(isinstance(inventory, list) and inventory, "overlay inventory missing")
        paths = [item.get("path", "") for item in inventory]
        require(len(paths) == len(set(paths)), "overlay inventory contains duplicate paths")
        for path in paths:
            rel = Path(path)
            require(path and not rel.is_absolute() and ".." not in rel.parts, f"unsafe inventory path: {path}")
        payload = {path.relative_to(stage).as_posix() for path in stage.rglob("*") if path.is_file()}
        allowed = {".devtool-artifact.json", ".devtool-artifact/validate.py"} | {item["path"] for item in inventory}
        require(payload == allowed, f"overlay payload/inventory mismatch extra={sorted(payload - allowed)} missing={sorted(allowed - payload)}")
        validator = stage / ".devtool-artifact/validate.py"
        expected_validator = metadata.get("validation", {}).get("script", {}).get("sha256", "")
        require(expected_validator == sha256_file(validator), "validator SHA-256 mismatch")
        candidate = extract_baseline(baseline, base)
        workspace = metadata.get("workspace", ".")
        require((candidate / workspace).is_dir(), f"workspace missing in baseline: {workspace}")
        current = repository_root(repo)
        for item in inventory:
            rel = Path(item["path"])
            source = stage / rel
            destination = candidate / rel
            action = item.get("action")
            require(action in {"add", "modify", "delete"}, f"unsupported inventory action: {action}")
            if action in {"modify", "delete"}:
                require(destination.is_file(), f"source missing for {action}: {rel}")
                require(item.get("source_sha256") == sha256_file(destination), f"source hash mismatch: {rel}")
            if action == "add":
                require(not destination.exists(), f"add conflicts with existing baseline path: {rel}")
            if action == "delete":
                destination.unlink()
            else:
                require(source.is_file(), f"payload missing: {rel}")
                require(item.get("sha256") == sha256_file(source), f"payload hash mismatch: {rel}")
                require(item.get("size") == source.stat().st_size, f"payload size mismatch: {rel}")
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, destination)
        run = subprocess.run([sys.executable, str(validator), "--repo-root", str(candidate)], capture_output=True, text=True)
        require(run.returncode == 0, f"embedded validator failed: {run.stdout}\n{run.stderr}")
        current_snapshot = source_snapshot(current, include_manifest=True)
        replay_snapshot = source_snapshot(candidate, include_manifest=True)
        missing = sorted(set(current_snapshot) - set(replay_snapshot))
        extra = sorted(set(replay_snapshot) - set(current_snapshot))
        changed = sorted(path for path in set(current_snapshot) & set(replay_snapshot) if current_snapshot[path] != replay_snapshot[path])
        require(not missing and not extra and not changed,
                f"replayed complete source tree differs from current source missing={missing[:10]} extra={extra[:10]} changed={changed[:10]}")
        replay_android = candidate / workspace
        replay_tree = verify_expected_tree(replay_android)
        current_tree = verify_expected_tree(repo)
        require(replay_tree["repository_tree_sha256"] == current_tree["repository_tree_sha256"], "replay/current source-tree seal mismatch")
        return {
            "status": "PASS",
            "detail": f"exact overlay replay matched the complete {len(current_snapshot)}-file source tree and {len(inventory)} inventory entries",
            "captured_at": now_ms(),
            "overlay_sha256": sha256_file(overlay),
            "baseline_sha256": sha256_file(baseline) if baseline.is_file() else "directory",
            "inventory_count": len(inventory),
            "source_file_count": len(current_snapshot),
            "source_seal_sha256": current_tree["repository_tree_sha256"],
            "validator_stdout": run.stdout.strip(),
        }


def git_finalization(root: Path) -> dict:
    try:
        repo = repository_root(root)
        top = subprocess.check_output(["git", "-C", str(root), "rev-parse", "--show-toplevel"], text=True, stderr=subprocess.STDOUT).strip()
        require(Path(top).resolve() == repo, "Git top-level does not match the repository root")
        status = subprocess.check_output(["git", "-C", str(repo), "status", "--porcelain=v1", "--untracked-files=all"], text=True)
        require(not status.strip(), "Git working tree is not clean: " + status.strip().replace("\n", "; "))
        head = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
        require(re.fullmatch(r"[0-9a-f]{40,64}", head) is not None, "invalid Git HEAD")
        tree = verify_expected_tree(root)
        current = source_snapshot(repo, include_manifest=True)
        tracked_raw = subprocess.check_output(["git", "-C", str(repo), "ls-files", "-z"], text=False)
        tracked_all = {Path(item.decode("utf-8")) for item in tracked_raw.split(b"\0") if item}
        tracked = {path.as_posix() for path in tracked_all if not is_transient(path)}
        require(set(current) == tracked,
                f"Git tracked source set mismatch untracked_source={sorted(set(current)-tracked)[:10]} unexpected_tracked={sorted(tracked-set(current))[:10]}")
        for rel, meta in current.items():
            object_hash = subprocess.check_output(["git", "-C", str(repo), "hash-object", rel], text=True).strip()
            index_line = subprocess.check_output(["git", "-C", str(repo), "ls-files", "-s", "--", rel], text=True).strip()
            require(index_line, f"source file missing from Git index: {rel}")
            index_hash = index_line.split()[1]
            require(object_hash == index_hash, f"Git index differs from source file: {rel}")
        return {
            "status": "PASS",
            "detail": "Git HEAD, index, working tree, tracked source set, and exact repository source seal all match",
            "captured_at": now_ms(),
            "head": head,
            "source_file_count": len(current),
            "source_seal_sha256": tree["repository_tree_sha256"],
        }
    except Exception as error:
        return block_result(str(error))


def run_profgen(profgen: str, apk: Path, profile_bytes: bytes, descriptors: list[str]) -> dict:
    executable = str(Path(profgen).resolve()) if Path(profgen).is_file() else shutil.which(profgen)
    require(bool(executable), f"profgen not found: {profgen}")
    with tempfile.TemporaryDirectory(prefix="phase31-profgen-") as temporary:
        profile = Path(temporary) / "baseline.prof"
        output = Path(temporary) / "baseline-profile.txt"
        profile.write_bytes(profile_bytes)
        run = subprocess.run(
            [str(executable), "dumpProfile", "--profile", str(profile), "--apk", str(apk),
             "--strict", "true", "--output", str(output)],
            capture_output=True, text=True,
        )
        require(run.returncode == 0, f"profgen rejected packaged Baseline Profile: {run.stdout}\n{run.stderr}")
        require(output.is_file() and output.stat().st_size > 0, "profgen produced no Baseline Profile dump")
        dump = output.read_text(encoding="utf-8", errors="replace")
    require(any(descriptor in dump for descriptor in descriptors),
            "profgen dump has no generated XBS profile descriptor")
    return {"profgen": str(executable), "profgen_dump_sha256": sha256_bytes(dump.encode("utf-8"))}


def verify_apk(root: Path, apk: Path, apksigner: str, profgen: str, expected_cert: str, evidence_asset: Path,
               host: dict, final_receipt: dict, session: dict, secret: bytes) -> dict:
    verify_bound_evidence(host, "host_evidence", session, secret)
    verify_bound_evidence(final_receipt, "final_receipt", session, secret)
    require(host.get("status") == "PASS" and host.get("blocked") == [], "host evidence is not a complete PASS")
    require(final_receipt.get("status") == "PASS", "final build receipt did not pass")
    require(host.get("source_seal_sha256") == session["repository_tree_sha256"], "host source seal mismatch")
    require(final_receipt.get("source_seal_sha256") == session["repository_tree_sha256"], "final receipt source seal mismatch")
    require(final_receipt.get("promise_seal_sha256") == session["promise_seal_sha256"], "final receipt promise seal mismatch")
    require(re.fullmatch(r"[0-9a-f]{40,64}", str(final_receipt.get("final_git_head", ""))) is not None,
            "final receipt Git HEAD is invalid")
    require(final_receipt.get("host_evidence_sha256") == sha256_bytes(canonical_json_bytes(host)), "final receipt host evidence binding mismatch")
    require(final_receipt.get("host_evidence_asset_sha256") == sha256_file(evidence_asset), "final receipt host asset binding mismatch")
    final_paths = verify_output_records(repository_root(root), final_receipt, "final_apk", session)
    require(len(final_paths) == 1 and final_paths[0].resolve() == apk.resolve(), "verified APK does not match the final build receipt")
    require(int(final_receipt.get("captured_at", 0)) >= int(host.get("captured_at", 0)), "final build receipt predates host evidence")
    expected = normalize_hex(expected_cert)
    require(len(expected) == 64, "expected certificate fingerprint must be 64 hex characters")
    require(apk.is_file(), f"release APK missing: {apk}")
    require(evidence_asset.is_file(), f"generated evidence asset missing: {evidence_asset}")
    signer = str(Path(apksigner).resolve()) if Path(apksigner).is_file() else shutil.which(apksigner)
    require(bool(signer), f"apksigner not found: {apksigner}")
    run = subprocess.run([str(signer), "verify", "--verbose", "--print-certs", str(apk)], capture_output=True, text=True)
    require(run.returncode == 0, f"apksigner verification failed: {run.stdout}\n{run.stderr}")
    combined = run.stdout + "\n" + run.stderr
    fingerprints = [normalize_hex(match) for match in re.findall(r"certificate SHA-256 digest:\s*([^\r\n]+)", combined, re.I)]
    require(len(fingerprints) == 1 and set(fingerprints) == {expected}, f"release signer set mismatch: found={fingerprints}")
    require(re.search(r"Verified using v2 scheme.*:\s*true", combined, re.I), "APK Signature Scheme v2 was not verified")
    expected_asset = asset_bytes(host)
    require(evidence_asset.read_bytes() == expected_asset, "host evidence asset file is not the canonical host evidence serialization")
    with zipfile.ZipFile(apk) as archive:
        try:
            packaged_asset = archive.read("assets/phase31_release_evidence.properties")
        except KeyError as error:
            raise GateError("APK has no assets/phase31_release_evidence.properties") from error
        require(packaged_asset == expected_asset, "packaged host evidence does not match canonical host evidence")
        try:
            profile_bytes = archive.read("assets/dexopt/baseline.prof")
            metadata_bytes = archive.read("assets/dexopt/baseline.profm")
        except KeyError as error:
            raise GateError(f"APK profile entry missing: {error}") from error
    profile_info = profile_zip_info(apk)
    generated_gate = host.get("gates", {}).get("generated_baseline_profile", {})
    expected_profile_sha = generated_gate.get("candidate_packaged_profile_sha256", "")
    expected_metadata_sha = generated_gate.get("candidate_packaged_profile_metadata_sha256", "")
    require(profile_info["profile_sha256"] == expected_profile_sha, "final packaged profile differs from the clean candidate profile")
    require(profile_info["profile_metadata_sha256"] == expected_metadata_sha, "final packaged profile metadata differs from the clean candidate metadata")
    descriptors = generated_gate.get("sample_descriptors", [])
    require(isinstance(descriptors, list) and descriptors, "generated profile descriptors are missing from host evidence")
    profgen_result = run_profgen(profgen, apk, profile_bytes, descriptors)
    captured = now_ms()
    result = {
        "status": "PASS",
        "detail": "APK exact signer set, canonical bundled host evidence, and ART-validated Baseline Profile verified",
        "captured_at": captured,
        "apk": str(apk),
        "apk_sha256": sha256_file(apk),
        "certificate_sha256": expected,
        "certificate_set": fingerprints,
        "host_evidence_asset_sha256": sha256_bytes(expected_asset),
        "host_evidence_sha256": sha256_bytes(canonical_json_bytes(host)),
        "final_receipt_sha256": sha256_bytes(canonical_json_bytes(final_receipt)),
        "source_seal_sha256": session["repository_tree_sha256"],
        **profile_info,
        **profgen_result,
        "gates": {
            "signed_apk": pass_result("apksigner verified one exact current release certificate and the canonical bundled host evidence", captured_at=captured),
            "packaged_baseline_profile": pass_result("profgen strictly parsed the final ART profile, matched generated XBS descriptors, and candidate/final profile hashes are identical", captured_at=captured),
        },
    }
    return bind_evidence(result, "apk_evidence", session, secret)


def exact_gate_set(gates: object, expected: tuple[str, ...], label: str) -> dict:
    require(isinstance(gates, dict), f"{label} gates must be an object")
    require(set(gates) == set(expected), f"{label} gate set mismatch missing={sorted(set(expected)-set(gates))} extra={sorted(set(gates)-set(expected))}")
    return gates


def validate_gate_item(item: object, gate: str, session: dict, not_after: int) -> dict:
    require(isinstance(item, dict), f"gate evidence must be an object: {gate}")
    require(set(item) >= {"status", "detail", "captured_at"}, f"gate evidence fields missing: {gate}")
    require(item.get("status") == "PASS", f"gate is not PASS: {gate}")
    require(bool(str(item.get("detail", "")).strip()), f"gate detail is empty: {gate}")
    captured = int(item.get("captured_at", 0))
    validate_timestamp(captured, session)
    require(captured <= not_after, f"gate timestamp is later than its envelope: {gate}")
    return item


def finalize(host: dict, apk: dict, session: dict, secret: bytes) -> dict:
    verify_bound_evidence(host, "host_evidence", session, secret)
    verify_bound_evidence(apk, "apk_evidence", session, secret)
    require(host.get("status") == "PASS" and host.get("blocked") == [], "host evidence top-level status is not a complete PASS")
    require(apk.get("status") == "PASS", "APK evidence top-level status is not PASS")
    require(host.get("source_seal_sha256") == session["repository_tree_sha256"], "host source seal does not match session")
    require(host.get("promise_seal_sha256") == session["promise_seal_sha256"], "host promise seal does not match session")
    require(HEX64_RE.fullmatch(str(host.get("candidate_receipt_sha256", ""))) is not None, "candidate receipt SHA-256 is invalid")
    require(host.get("promise_seal_sha256") == session["promise_seal_sha256"], "host promise seal does not match session")
    require(apk.get("source_seal_sha256") == session["repository_tree_sha256"], "APK evidence source seal does not match session")
    require(apk.get("host_evidence_sha256") == sha256_bytes(canonical_json_bytes(host)), "APK evidence host binding mismatch")
    require(HEX64_RE.fullmatch(str(apk.get("final_receipt_sha256", ""))) is not None, "final receipt SHA-256 is invalid")
    require(HEX64_RE.fullmatch(str(apk.get("apk_sha256", ""))) is not None, "APK SHA-256 is invalid")
    host_captured = int(host["captured_at"])
    apk_captured = int(apk["captured_at"])
    require(apk_captured >= host_captured, "APK evidence predates host evidence")
    require(apk.get("host_evidence_asset_sha256") == sha256_bytes(asset_bytes(host)), "APK evidence is not bound to canonical host evidence")
    certificate = str(apk.get("certificate_sha256", ""))
    require(HEX64_RE.fullmatch(certificate) is not None, "APK certificate SHA-256 is invalid")
    require(apk.get("certificate_set") == [certificate], "APK certificate set is not exactly the verified certificate")
    generated = host.get("gates", {}).get("generated_baseline_profile", {})
    require(apk.get("profile_sha256") == generated.get("candidate_packaged_profile_sha256"), "APK profile hash is not bound to host evidence")
    require(apk.get("profile_metadata_sha256") == generated.get("candidate_packaged_profile_metadata_sha256"), "APK profile metadata hash is not bound to host evidence")
    host_gates = exact_gate_set(host.get("gates"), HOST_GATES, "host")
    apk_gates = exact_gate_set(apk.get("gates"), ("signed_apk", "packaged_baseline_profile"), "APK")
    combined = {**host_gates, **apk_gates}
    ordered: dict[str, dict] = {}
    for gate in GATES:
        envelope_time = host_captured if gate in HOST_GATES else apk_captured
        ordered[gate] = validate_gate_item(combined[gate], gate, session, envelope_time)
    captured = now_ms()
    validate_timestamp(captured, session)
    result = {
        "status": "PASS",
        "detail": "all 14 session-bound Phase 31 release gates passed",
        "captured_at": captured,
        "source_seal_sha256": host["source_seal_sha256"],
        "promise_seal_sha256": host.get("promise_seal_sha256", ""),
        "apk_sha256": apk["apk_sha256"],
        "host_evidence_sha256": sha256_bytes(canonical_json_bytes(host)),
        "apk_evidence_sha256": sha256_bytes(canonical_json_bytes(apk)),
        "gates": ordered,
        "blocked": [],
    }
    return bind_evidence(result, "final_release_seal", session, secret)


def markdown_report(data: dict) -> str:
    lines = [
        "# Phase 31 Final Release Seal r3", "", f"Status: **{data.get('status')}**", "",
        f"Session: `{data.get('session_id', '')}`", f"App: {data.get('app_version_name')} ({data.get('app_version_code')})",
        f"Source seal: `{data.get('source_seal_sha256', '')}`", f"APK SHA-256: `{data.get('apk_sha256', '')}`", "", "## Gates", "",
    ]
    for gate in GATES:
        item = data.get("gates", {}).get(gate, {})
        lines.append(f"- **{item.get('status', 'BLOCKER')}** `{gate}`: {item.get('detail', 'missing')}")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("write-source-manifest")
    p.add_argument("--repo-root", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p = sub.add_parser("start-session")
    p.add_argument("--repo-root", type=Path, required=True)
    p.add_argument("--session", type=Path, required=True)
    p.add_argument("--secret", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p = sub.add_parser("record-candidate")
    p.add_argument("--repo-root", type=Path, required=True)
    p.add_argument("--session", type=Path, required=True)
    p.add_argument("--secret", type=Path, required=True)
    p.add_argument("--graph-input", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p = sub.add_parser("record-final")
    p.add_argument("--repo-root", type=Path, required=True)
    p.add_argument("--session", type=Path, required=True)
    p.add_argument("--secret", type=Path, required=True)
    p.add_argument("--graph-input", type=Path, required=True)
    p.add_argument("--host-evidence", type=Path, required=True)
    p.add_argument("--evidence-asset", type=Path, required=True)
    p.add_argument("--apk", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    for name in ("source-audit", "git-finalization"):
        p = sub.add_parser(name)
        p.add_argument("--repo-root", type=Path, required=True)
        p.add_argument("--session", type=Path, required=True)
        p.add_argument("--secret", type=Path, required=True)
        p.add_argument("--output", type=Path, required=True)
    p = sub.add_parser("artifact-replay")
    p.add_argument("--repo-root", type=Path, required=True)
    p.add_argument("--overlay", type=Path, required=True)
    p.add_argument("--baseline", type=Path, required=True)
    p.add_argument("--session", type=Path, required=True)
    p.add_argument("--secret", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p = sub.add_parser("collect-evidence")
    p.add_argument("--repo-root", type=Path, required=True)
    p.add_argument("--session", type=Path, required=True)
    p.add_argument("--secret", type=Path, required=True)
    p.add_argument("--candidate-receipt", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--asset", type=Path, required=True)
    p = sub.add_parser("verify-apk")
    p.add_argument("--repo-root", type=Path, required=True)
    p.add_argument("--session", type=Path, required=True)
    p.add_argument("--secret", type=Path, required=True)
    p.add_argument("--host-evidence", type=Path, required=True)
    p.add_argument("--final-receipt", type=Path, required=True)
    p.add_argument("--apk", type=Path, required=True)
    p.add_argument("--apksigner", required=True)
    p.add_argument("--profgen", required=True)
    p.add_argument("--expected-cert", required=True)
    p.add_argument("--evidence-asset", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p = sub.add_parser("finalize")
    p.add_argument("--repo-root", type=Path, required=True)
    p.add_argument("--session", type=Path, required=True)
    p.add_argument("--secret", type=Path, required=True)
    p.add_argument("--host-evidence", type=Path, required=True)
    p.add_argument("--apk-evidence", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--markdown", type=Path, required=True)
    args = parser.parse_args(argv)
    output = args.output
    try:
        root = args.repo_root.resolve()
        if args.command == "write-source-manifest":
            result = build_expected_tree_manifest(root)
            write_json(args.output, result)
            print(json.dumps(result, sort_keys=True))
            return 0
        if args.command == "start-session":
            session = start_session(root, args.session.resolve(), args.secret.resolve())
            session["session_dir"] = str(args.session.resolve().parent)
            write_json(args.session.resolve(), session)
            result = dict(session)
        else:
            session = load_session(args.session.resolve())
            secret = load_secret(args.secret.resolve())
            if args.command == "record-candidate":
                result = record_candidate(root, session, secret, args.graph_input.resolve())
            elif args.command == "record-final":
                result = record_final(root, session, secret, args.graph_input.resolve(),
                                      args.host_evidence.resolve(), args.evidence_asset.resolve(), args.apk.resolve())
            elif args.command == "source-audit":
                result = bind_evidence(source_audit(root), "source_audit", session, secret)
            elif args.command == "git-finalization":
                result = bind_evidence(git_finalization(root), "git_finalization", session, secret)
            elif args.command == "artifact-replay":
                result = bind_evidence(artifact_replay(root, args.overlay.resolve(), args.baseline.resolve()), "artifact_replay", session, secret)
            elif args.command == "collect-evidence":
                result = collect_evidence(root, session, secret, args.candidate_receipt.resolve())
                write_asset(args.asset.resolve(), result)
            elif args.command == "verify-apk":
                host = read_json(args.host_evidence.resolve())
                final_receipt = read_json(args.final_receipt.resolve())
                result = verify_apk(root, args.apk.resolve(), args.apksigner, args.profgen, args.expected_cert,
                                    args.evidence_asset.resolve(), host, final_receipt, session, secret)
            else:
                host = read_json(args.host_evidence.resolve())
                apk = read_json(args.apk_evidence.resolve())
                result = finalize(host, apk, session, secret)
                args.markdown.parent.mkdir(parents=True, exist_ok=True)
                args.markdown.write_text(markdown_report(result), encoding="utf-8")
    except Exception as error:
        result = {"format": FORMAT_VERSION, "status": "BLOCKER", "detail": str(error), "captured_at": now_ms()}
    write_json(output, result)
    print(json.dumps(result, sort_keys=True))
    return 0 if result.get("status") == "PASS" or args.command in {"start-session"} else 1


if __name__ == "__main__":
    raise SystemExit(main())
