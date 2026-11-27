# XBS R01B — Write-ahead recovery and command replay refusal

Pinned parent: `2d02abb8cd3fe94b6d3656174521ec11edb26fd8` (PR #2).
This overlay is a stacked change on `feature/xbs-r01a-rollback-ownership-20261112`.

## Implementation

- New tuning.Engine journal callback persists **next original-state snapshot before each reversible privileged effect**, with errors blocking mutation. Release of records is also journaled; errors retain in-memory records and return an explicit restoration failure.
- Daemon binds callback to its version-1 runtime state file. The existing atomic-write path is upgraded to report directory-sync errors. `Main` refuses the control loop if startup restore or reapplication fails, recording `recovery_required` status.
- Restart reconciliation restores recorded reversible effects *before* reapplying the last durably active profiles. A newly started command whose activation wasn't persisted is rolled back rather than silently replayed. A prior `recovery_required` mode is reconciled into safe mode, **without automatic profile reactivation**. Dry-run refuses pending real recovery.
- Doze's XBS-owned force-idle operation has a durable `doze:owned` marker and is unforced only when recorded; it cannot prove externally forced idle ownership, so API36 device verification remains mandatory.
- Queue `.work` claims with no completed ack produce an explicit failed/interrupted receipt and are quarantined; they are not re-executed. Invalid IDs cannot form paths outside the ack directory.
- Daemon does not delete claimed work files when ack persistence fails.
- Added Go fault-injection tests for write-ahead barriers, failed release persistence, crash-snapshot recovery and queue interruption.

## Unresolved and not qualified

The ledger remains **IN_PROGRESS**, not accepted. Package state may change after preflight; `renice` recovery is not yet bound to stable process identity. Multi-profile effect ownership and reconciliation require an additional redesign. Forced-idle state cannot be precisely observed through all vendor APIs; synthetic and real-device gates must cover differences. The generic legacy control file is still separate from the durable queue. Some path and symlink hardening remains for R02. There is no root-qualified device or Android emulator evidence in this overlay.

Use full GitHub Go tests/vet/contracts/module build on the **exact commit**. No local Gradle, no root actions, no auto merge or force push.

## HF01 fresh-claim regression correction

The first R01B ZIP mistakenly classified its newly renamed `.json` -> `.work` claim as a stranded crash request. HF01 distinguishes a file claimed **within the current `NextRequest` call** from an older `.work` file found during directory enumeration. It preserves interrupted-claim quarantine, includes a 12-item fresh-claim drain test and an orphan-claim refusal test, and retains the same exact R01A base commit. The failed first application must be completely rolled back before HF01 is applied.

## HF02 cross-package CLI queue compatibility fix

The first HF01 repair fixed fresh `.json` claims, but the existing `internal/cli/cli_test.go` still expected an unacknowledged `.work` to be safely returned by a second `NextRequest` call. This violates R01B's no-blind-replay contract and caused the full repository `go test ./...` failure (even though targeted tuning/daemon/control suites passed). HF02 updates that integration test to durably acknowledge the initial reload, leave its `.work` for automatic acknowledged-claim cleanup, then require the next queued `start manual` request in order. A matching control-layer regression is exercised in the embedded isolated Go harness. No production queue replay guard is weakened.

The failed user-side attempt reported `XBS_R01B_PRECOMMIT_ROLLBACK_APPLIED`; this package still requires the exact unchanged R01A commit. Full real-repository Go tests and hosted CI must execute before qualification.
