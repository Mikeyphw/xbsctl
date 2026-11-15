# XBSctl — Handoff v3: GX first, original roadmap preserved

**Date:** 2026-11-14 · **Type:** planning and evidence reconciliation only, not an implementation overlay.

## Source authority

- **Original canonical plan:** `archive/v2_original/XBS_FINAL_ROADMAP_v2.md`, unchanged (R00–R13, 178 tasks, 131 cases).
- **New temporary side plan:** `XBS_GAP_CLOSURE_SIDE_ROADMAP_v1.md`, six consolidated GX milestones and 40 new side tasks, separate counters.
- **Current observed progress:** `XBS_PROGRESS_SNAPSHOT_20261114.md` and `.json`.
- **Machine-readable tracking snapshots:** `XBS_IMPLEMENTATION_LEDGER_v3.json` and `XBS_TEST_MATRIX_v3.json`.
- **Reconciliation instructions:** `XBS_LEDGER_RECONCILIATION_PROTOCOL.md`.

## Why this is necessary

The original ledger/test matrix lag current cumulative hosted implementation. R09F exact-SHA Go/JVM/API36 passed and R06–R09 code exists, but the original 131 case IDs remain unmapped, original R08 native QS and parts of original R10 are missing, physical R11 and release R13 are not qualified. Calling a stack integration script “R10A” did not close original R10.

## Operating rule

**Do GX01–GX06 first, in substantial consolidated overlays.** During GX work, map source-verified tests back to original IDs and never erase original tasks. After GX06, resume the original plan at real remaining dependencies and gates. No hidden background work is scheduled, no Git write has happened from this handoff.

## Source and CI state

- Latest hosted R09F PR #26 SHA: `e8647e8f80833d26fe6a6164725ae493f4a37301`.
- Verified Actions run: https://github.com/Mikeyphw/xbsctl/actions/runs/43650947189.
- Latest R09F measured Go statement coverage: 72.09%, seven risk packages; no real device or signed release qualification.
- R10A stack-integration overlay: locally built, not applied to remote PR at time of review.

## Usage

- Read `XBS_GAP_CLOSURE_SIDE_ROADMAP_v1.md` followed by `XBS_PROGRESS_SNAPSHOT_20261114.md`.
- Verify ZIP in-place (read-only): `python3 XBS_HANDOFF_V3_GAP_CLOSURE_20261114.zip --check`.
- Optional summary: `python3 XBS_HANDOFF_V3_GAP_CLOSURE_20261114.zip --overview`.
- **This ZIP is documentation, not an overlay to apply.** It does not push, dispatch, merge, install, use Gradle, or touch the device.
