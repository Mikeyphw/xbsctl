from __future__ import annotations

import os
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TESTS = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"
XBS = ROOT / "apps/xbs"

MODULE_PATHS = [
    "/data/adb/modules/XtremeBS/system/bin/xbs",
    "/data/adb/modules_update/XtremeBS/system/bin/xbs",
    "/data/adb/ksu/modules/XtremeBS/system/bin/xbs",
    "/data/adb/ksu/modules_update/XtremeBS/system/bin/xbs",
]


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_android_runtime_resolver_centralizes_all_root_module_paths() -> None:
    resolver = text(JAVA / "XbsRuntimeResolver.java")
    assert 'MODULE_ID = "XtremeBS"' in resolver
    for root in (
        "/data/adb/modules/",
        "/data/adb/modules_update/",
        "/data/adb/ksu/modules/",
        "/data/adb/ksu/modules_update/",
    ):
        assert root in resolver
    assert 'static String resolveXbs(' in resolver
    assert 'static String resolveXbsCtl(' in resolver
    assert 'static String resolveYq(' in resolver
    assert 'static String xbsJsonError(' in resolver


def test_scattered_android_callers_use_the_shared_resolver() -> None:
    callers = {
        "XbsRootApi.java": ["XbsRuntimeResolver.availability", "XbsRuntimeResolver.resolveXbs"],
        "RootDiagnosticsProbe.java": ["XbsRuntimeResolver.resolveXbs"],
        "EventBridgeReader.java": ["XbsRuntimeResolver.probeCalls"],
        "RootCommandRunner.java": ["XbsRuntimeResolver.xbsJsonError", "XbsRuntimeResolver.xbsCtlJsonError"],
        "XbsCompatibilityProbe.java": ["XbsRuntimeResolver.xbsJsonError"],
        "XbsConfigApi.java": ["XbsRuntimeResolver.xbs", "XbsRuntimeResolver.resolveDefaultEssentials"],
        "XbsConfigTransaction.java": ["XbsRuntimeResolver.xbs", "XbsRuntimeResolver.xbsCtl", "XbsRuntimeResolver.resolveYq"],
    }
    for filename, tokens in callers.items():
        source = text(JAVA / filename)
        for token in tokens:
            assert token in source, f"{filename} missing {token}"
    for source_file in JAVA.glob("*.java"):
        source = text(source_file)
        if source_file.name == "XbsRuntimeResolver.java":
            continue
        assert "/data/adb/modules_update/XtremeBS/system/bin/xbs" not in source
        assert "/data/adb/ksu/modules/XtremeBS/system/bin/xbs" not in source
        assert "if [ -x /system/bin/xbs ]" not in source
        assert "if [ -x /system/bin/XBSctl ]" not in source


def test_junit_order_assertions_do_not_match_system_substring_inside_module_paths() -> None:
    runtime_test = text(TESTS / "XbsRuntimeResolverTest.java")
    bridge_test = text(TESTS / "EventBridgeEnvelopeTest.java")
    assert 'lastIndexOf(systemCandidate)' in runtime_test
    assert 'lastIndexOf(systemCandidate)' in bridge_test
    assert 'command.indexOf("/system/bin/xbs")' not in bridge_test
    assert 'shell.indexOf("/system/bin/xbs")' not in runtime_test
    assert 'module candidate should be probed before explicit system fallback' in runtime_test
    assert 'module candidate should be probed before explicit system fallback' in bridge_test




def test_config_transaction_reload_test_accepts_resolved_xbsctl_launcher() -> None:
    junit = text(TESTS / "XbsConfigTransactionTest.java")
    assert '\\"$XBSCTL_BIN\\" --json reload' in junit
    assert 'assertTrue(shell.contains("XBSctl --json reload"))' not in junit
    assert '/data/adb/modules_update/XtremeBS/system/bin/XBSctl' in junit
    assert '/data/adb/ksu/modules/XtremeBS/system/bin/XBSctl' in junit
    assert '/data/adb/ksu/modules_update/XtremeBS/system/bin/XBSctl' in junit

def test_bridge_parser_handles_string_usage_shape_without_jsonobject_exception() -> None:
    envelope = text(JAVA / "EventBridgeEnvelope.java")
    junit = text(TESTS / "EventBridgeEnvelopeTest.java")
    assert 'typedJsonFallback("data.bridge", bridgeValue)' in envelope
    assert 'Root verifier output used the wrong JSON type' in envelope
    assert 'Root verifier emitted CLI usage in ' in envelope
    assert 'stringBridgeUsageObjectIsAvailabilityFallback' in junit
    assert 'wrongJsonTypeWithoutUsageFailsClosed' in junit


def test_root_module_launchers_resolve_magisk_update_and_ksu_roots() -> None:
    for rel in (
        "system/bin/xbs",
        "module/system/bin/xbs",
        "system/bin/XBSctl",
        "module/system/bin/XBSctl",
        "system/bin/XtremeBSd",
        "module/system/bin/XtremeBSd",
        "bin/xbs-paths.sh",
        "module/bin/xbs-paths.sh",
    ):
        source = text(XBS / rel)
        assert "/data/adb/modules/" in source
        assert "/data/adb/modules_update/" in source
        assert "/data/adb/ksu/modules/" in source
        assert "/data/adb/ksu/modules_update/" in source
    assert '"$MODDIR/system/bin/XtremeBSd"' in text(XBS / "module/service.sh")
    assert 'CTL="$MODDIR/system/bin/XBSctl"' in text(XBS / "module/action.sh")


def test_xbs_module_zip_preserves_executable_launchers(tmp_path: Path) -> None:
    out = tmp_path / "xbs-module.zip"
    env = dict(os.environ)
    env.setdefault("PYTHONPATH", str(ROOT))
    result = subprocess.run(
        [sys.executable, str(ROOT / "apps/xbs/install.py"), "build-module", "--platform", "android", "--out", str(out)],
        cwd=ROOT,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=120,
    )
    assert result.returncode == 0, result.stderr or result.stdout
    with zipfile.ZipFile(out) as archive:
        for name in (
            "system/bin/xbs",
            "system/bin/XBSctl",
            "system/bin/XtremeBSd",
            "system/bin/bash",
            "bin/yq",
            "bin/xbs-paths.sh",
            "service.sh",
            "customize.sh",
            "action.sh",
        ):
            mode = (archive.getinfo(name).external_attr >> 16) & 0o777
            assert mode & 0o111, f"{name} should be executable in the module zip, got {mode:o}"


def test_root_diagnostics_repair_shell_escapes_xbs_init_java_quotes() -> None:
    source = text(JAVA / "RootDiagnosticsProbe.java")
    assert 'if [ -n \\"$XBS_INIT\\" ]; then \\"$XBS_INIT\\" --json init >/dev/null;' in source
    assert 'if [ -n "$XBS_INIT" ]; then "$XBS_INIT" --json init >/dev/null;' not in source
