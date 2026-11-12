# XBS R03C — Event reliability and Android API 36 read-only diagnostics (HF01)

**R03C original base:** `c5fc976749df7830308dc88d9a4577a21c689c58` (PR #10).
**HF01 exact source base:** `b9d1bed74eb36861ce7ed642c6e4de9236c6376b` (R03C PR #11).

## Existing R03C functionality retained

- Read-only, nonrepairing queue inspection of queued, claimed, acknowledged and missing commands.
- Ten-minute automatic pending watchdog, strict origin validation, no uncertain-command replay.
- Deterministic missed-transition and fault-injection tests; root/device qualification remains pending.
- Explicit user-authorized ADB battery/power inspection; no root or Android mutation commands.

## HF01 diagnostic hardening

- Distinguishes nonzero ADB exit (includes bounded stderr/stdout summary), 20-second read timeout, and true 2 MiB oversize. Previously all of these looked like the same `adb read refused` error, with a 50,000-character cutoff that incorrectly rejected legitimate power dumps.
- Uses an exact allowlist of seven read-only `adb shell` commands. Validates serial/API 36/ARM64/battery sensor values, refusing malformed or unsupported mandatory readings.
- When `dumpsys power` cannot confirm exactly one `mWakefulness=Awake|Asleep`, tries read-only `dumpsys input_method` (`mInteractive`) and then `dumpsys display` (`mScreenState`). These alternate observations do **not** establish PowerManager wakefulness; the output has `wakefulness: null`, an explicit `PARTIAL_NOT_ROOT_ACCEPTANCE` status and bounded warnings. If no display service is readable, mandatory battery data are still reported as partial.
- Optional `--require-wakefulness` enforces the original fail-closed behavior with actionable error details; no partial reading may satisfy an automated physical acceptance gate.
- Output is diagnostic only; no root, appops, settings writes, input changes, privileged profile transitions, background service manipulation, or automatic approval.
- Full original R03C Go source unchanged. PR #11 remains the complete R03C implementation; HF01 modifies the Python diagnostic and its unit tests and implementation note only.

## Qualification

Before commit: exact GitHub source blobs, full Go `go test ./...`, `go vet ./...`, `xbs contracts check`, `xbsrepo final-seal`, Python probe tests and source/ZIP integrity. The ZIP self-test runs inherited Go fixtures, real probe entrypoint against fake ADB, strict-mode failure and negative security mutations. Hosted CI, actual user device reads and rooted Android lifecycle acceptance are separate gates.

R03D should collect actual API 36 evidence for suspend/resume timing and missed transitions, but never infer privileged lifecycle acceptance from a partial read-only probe.
