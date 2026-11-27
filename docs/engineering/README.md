# XBSctl engineering ledger — first implementation tranche

This is a copy of the comprehensive 14-phase, 178-task, 131-test roadmap,
updated to reflect a **prepared and unverified** source-pinned implementation tranche.

## Source baseline

- Repository: `Mikeyphw/xbsctl`
- Source: `f048d2d5526a3d0d7632e477b6b4f052e41e30df`
- Overlay: `XBS_R00_R02_CODE_BOOTSTRAP_20261112.zip`
- GitHub direct branch creation attempted and refused by integration with HTTP 403.
- No remote writes, Android builds, CI runs, device/root tests or qualifications have occurred.

## First source slice

- R00-02: standalone Gradle .devtool manifest path
- R02-01: exact CLI controller exit codes
- R02-07: fail-closed initial semantic config validation
- R01-08..10: reject failed or unknown GMS, Wi-Fi and low-RAM original-state probes
- R04-01..02: remove missing install helper dependency and initialize config on boot
- New Go negative regression suites and source-bound overlay self-tests

The first slice does **not** solve the remaining P0 issues: crash-safe journaling,
per-package suspension ownership, essentials policy or command replay. Those
remain OPEN and explicitly unqualified. No QS tile implementation is included yet.

## Ledger operation

Run `python3 XBS_LEDGER_CHECK.py` in this directory. Do not label tests
`PASS_AT_EXACT_SHA` until an actual test runner provides a nonzero test count,
assertion evidence, exact commit, CI run and run attempt.

**Important:** `IN_PROGRESS` means work planned/prepared, not accepted.
