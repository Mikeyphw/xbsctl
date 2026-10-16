# Phase 32D: Root-module runtime resolver and bridge parser hardening

This hotfix aligns the Android app with the root module that this repository actually builds.

## Problem

The Android app had scattered binary checks for `xbs`, `XBSctl`, and `yq`. Several callers preferred `/system/bin` first or only checked `/data/adb/modules/XtremeBS`, so a valid module staged under `modules_update` or KernelSU layouts could be missed. If a stale shim was found, `xbs --json event-bridge` could emit usage-shaped output instead of the authenticated bridge object, which surfaced as JSON object conversion errors such as `Value usage of type java.lang.String cannot be converted to JSONObject`.

## Runtime search order

Android now resolves XBS executables through `XbsRuntimeResolver` and probes module paths first:

1. `/data/adb/modules/XtremeBS/system/bin/<tool>`
2. `/data/adb/modules_update/XtremeBS/system/bin/<tool>`
3. `/data/adb/ksu/modules/XtremeBS/system/bin/<tool>`
4. `/data/adb/ksu/modules_update/XtremeBS/system/bin/<tool>`
5. `/system/bin/<tool>`

The same policy covers `xbs` and `XBSctl`. `yq` resolves from the module `bin/` directory first, then `/system/bin/yq`, then `command -v yq`.

## Parser hardening

The authenticated event bridge parser now inspects JSON value types before treating `data`, `data.bridge`, `payload`, `event`, or `status` as objects. Usage-shaped strings are demoted to the availability fallback path. Other wrong JSON types fail closed as `invalid_machine_output`.

## Root module fixes

The repo-built module launchers now resolve Magisk, Magisk update, KernelSU, and KernelSU update module directories. `service.sh` and `action.sh` prefer `$MODDIR` direct launchers rather than depending on magic-mounted `/system/bin` visibility. The module builder re-applies executable modes after the module overlay is copied, so generated ZIPs preserve executable bits for launchers.

## Preserved boundaries

- Root execution remains allowlisted.
- Authenticated bridge failures still fail closed except for explicit availability/usage fallback codes.
- No WebView, HTTP backend, arbitrary shell bridge, or non-module daemon is introduced.
- The app still supports `/system/bin` only as a fallback after repo module paths.


## r2 compile hotfix

The original Phase 32D overlay accidentally embedded raw shell quotes in `RootDiagnosticsProbe.repairStoragePaths()`, producing Java compilation errors around the `XBS_INIT` branch. r2 keeps the same resolver and bridge-parser behavior, but escapes those shell quotes inside the Java string and adds validator/pytest coverage for the exact failure seen by Devtool.


## r3 release-unit-test correction

The r2 release unit tests used `indexOf("/system/bin/xbs")` when checking resolver order.
That substring also occurs inside the intended module candidate paths such as
`/data/adb/modules/XtremeBS/system/bin/xbs`, so later module roots could be
incorrectly compared against the first module candidate instead of the explicit
`/system/bin/xbs` fallback. r3 keeps the production resolver unchanged and
fixes the tests to compare against the quoted explicit fallback token using
`lastIndexOf(systemCandidate)`.


## r4 release-unit-test compatibility

The r4 overlay keeps the r2/r3 production code and fixes the remaining release
unit-test assertion that still expected the literal command text `XBSctl --json
reload`.  Config transactions now reload through `XbsRuntimeResolver.xbsCtl`, so
the test verifies the resolved `$XBSCTL_BIN` launcher, Magisk/KSU module
candidates, and absence of the stale bare command expectation.
