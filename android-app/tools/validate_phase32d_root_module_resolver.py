#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
JAVA = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TESTS = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"
XBS = ROOT / "apps/xbs"
COMMON = ROOT / "tools/install/common.py"

MODULE_ROOTS = [
    "/data/adb/modules/",
    "/data/adb/modules_update/",
    "/data/adb/ksu/modules/",
    "/data/adb/ksu/modules_update/",
]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def text(path: Path) -> str:
    require(path.is_file(), f"missing file: {path}")
    return path.read_text(encoding="utf-8")


def main() -> int:
    resolver = text(JAVA / "XbsRuntimeResolver.java")
    require('MODULE_ID = "XtremeBS"' in resolver, "resolver must pin the XtremeBS module id")
    for root in MODULE_ROOTS:
        require(root in resolver, f"resolver missing {root}")
    require(resolver.index('/data/adb/modules/') < resolver.index('/system/bin'), "module paths must be before /system/bin")
    for token in ("resolveXbs", "resolveXbsCtl", "resolveYq", "xbsJsonError", "xbsCtlJsonError", "resolveDefaultEssentials"):
        require(token in resolver, f"resolver missing {token}")

    callers = {
        "XbsRootApi.java": ("XbsRuntimeResolver.availability", "XbsRuntimeResolver.resolveXbs"),
        "RootDiagnosticsProbe.java": ("XbsRuntimeResolver.resolveXbs",),
        "EventBridgeReader.java": ("XbsRuntimeResolver.probeCalls",),
        "RootCommandRunner.java": ("XbsRuntimeResolver.xbsJsonError", "XbsRuntimeResolver.xbsCtlJsonError"),
        "XbsCompatibilityProbe.java": ("XbsRuntimeResolver.xbsJsonError",),
        "XbsConfigApi.java": ("XbsRuntimeResolver.xbs", "XbsRuntimeResolver.resolveDefaultEssentials"),
        "XbsConfigTransaction.java": ("XbsRuntimeResolver.xbs", "XbsRuntimeResolver.xbsCtl", "XbsRuntimeResolver.resolveYq"),
    }
    for name, tokens in callers.items():
        source = text(JAVA / name)
        for token in tokens:
            require(token in source, f"{name} missing {token}")
        if name == "RootDiagnosticsProbe.java":
            require('if [ -n \\"$XBS_INIT\\" ]; then \\"$XBS_INIT\\" --json init >/dev/null;' in source,
                    "RootDiagnosticsProbe repair shell must escape XBS_INIT quotes inside Java strings")
            require('if [ -n "$XBS_INIT" ]; then "$XBS_INIT" --json init >/dev/null;' not in source,
                    "RootDiagnosticsProbe contains unescaped XBS_INIT quotes that break javac")
        if name != "XbsRuntimeResolver.java":
            require('if [ -x /system/bin/xbs ]' not in source, f"{name} still checks /system/bin/xbs directly")
            require('if [ -x /system/bin/XBSctl ]' not in source, f"{name} still checks /system/bin/XBSctl directly")

    envelope = text(JAVA / "EventBridgeEnvelope.java")
    for token in ("typedJsonFallback", "data.bridge", "Root verifier output used the wrong JSON type", "verifier_usage"):
        require(token in envelope, f"EventBridgeEnvelope missing {token}")
    junit = text(TESTS / "EventBridgeEnvelopeTest.java") + text(TESTS / "XbsRuntimeResolverTest.java")
    for token in ("stringBridgeUsageObjectIsAvailabilityFallback", "wrongJsonTypeWithoutUsageFailsClosed", "xbsResolverPrefersRepoModuleBeforeSystemMount"):
        require(token in junit, f"JUnit coverage missing {token}")
    require("lastIndexOf(systemCandidate)" in junit, "JUnit resolver ordering assertions must target the explicit /system fallback, not module-path substrings")
    require('indexOf("/system/bin/xbs")' not in junit, "JUnit resolver ordering assertions still match /system/bin inside module paths")
    config_junit = text(TESTS / "XbsConfigTransactionTest.java")
    require('\\"$XBSCTL_BIN\\" --json reload' in config_junit, "config transaction release test must expect resolved XBSctl launcher reload")
    require('assertTrue(shell.contains("XBSctl --json reload"))' not in config_junit, "config transaction test still expects bare XBSctl reload")
    for path in ("/data/adb/modules_update/XtremeBS/system/bin/XBSctl", "/data/adb/ksu/modules/XtremeBS/system/bin/XBSctl", "/data/adb/ksu/modules_update/XtremeBS/system/bin/XBSctl"):
        require(path in config_junit, f"config transaction test missing resolver coverage for {path}")

    for rel in (
        "system/bin/xbs", "module/system/bin/xbs", "system/bin/XBSctl", "module/system/bin/XBSctl",
        "system/bin/XtremeBSd", "module/system/bin/XtremeBSd", "bin/xbs-paths.sh", "module/bin/xbs-paths.sh",
    ):
        source = text(XBS / rel)
        for root in MODULE_ROOTS:
            require(root in source, f"{rel} missing {root}")
    require('"$MODDIR/system/bin/XtremeBSd"' in text(XBS / "module/service.sh"), "service must use MODDIR direct daemon launch")
    require('CTL="$MODDIR/system/bin/XBSctl"' in text(XBS / "module/action.sh"), "action.sh must use MODDIR direct XBSctl")
    common = text(COMMON)
    for rel in ("system/bin/xbs", "system/bin/XBSctl", "system/bin/XtremeBSd", "bin/yq", "bin/xbs-paths.sh"):
        require(rel in common, f"module builder must chmod {rel}")
    require("make_executable(target)" in common, "module builder must apply executable mode after overlay copy")
    print("Phase 32D root-module runtime resolver validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
