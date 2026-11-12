# R02B — Durable command lifecycle and unknown-outcome hold

**Exact source:** `Mikeyphw/xbsctl` branch `feature/xbs-r02a-secure-daemon-queue-20261112`, commit `f38a051e64b1b8b5b842f23f4b887597b9b65058` (PR #7).

## Changes

- After `.json -> .work` claim, durably create an immutable `.phase/<request-id>.claimed` marker. Before the daemon invokes any privileged operation, durably publish `.phase/<id>.applying` using the existing Android-safe `renameat2(RENAME_NOREPLACE)` primitive. This applies to the queued Go control path, not the deprecated legacy text control path.
- A claim interrupted BEFORE the `applying` marker gets a failed receipt, quarantine, and zero replays. An `applying` marker with no final acknowledgement—or an unclassifiable legacy claim—gets an immutable `unknown` receipt. The original `.work` remains in place, and no replay is attempted.
- Before any tuning recovery/reapplication at startup, the daemon scans the queue for uncertain claims and creates unknown receipts. Discovery enters `recovery_required`, writes status and refuses to start. It intentionally **does not** try to guess whether a privileged command committed.
- CLI `--wait` returns explicit `completion=unknown` and a nonzero exit code for that receipt, instead of incorrectly reporting failure/timeout.
- Append-only per-request phase markers tie version, ID, event and command to each request. No following symbolic links, no oversized records, no overwriting acknowledgements.

## Acceptance limits

The manual recovery procedure for a genuinely unknown root outcome is deliberately NOT automated: an operator must reconcile the original-state WAL and actual device state before clearing the hold. This is safer than blind reapplication. The phase contract does not yet provide an IPC broker, root permission transfer, bounded execution deadlines or cross-filesystem shared-storage support. Original process restoration and API36 physical qualification remain pending.

New tests: ten isolated Go control regression tests, two daemon startup/control integration tests, inherited R02A queue tests, race/vet, Android arm64 build, deterministic failure mutation. The overlay requires full `go test ./...`, `go vet ./...`, contracts and final-seal on the user's pinned repository before commit/push.

## Next 3 overlays

1. **R02C — Reconciliation and operator clearance:** verified recovery authorization, unknown-outcome evidence inspection, controlled holds clearance, bounded deadlines and crash-phase instrumentation.
2. **R03A — Automatic Event Lifecycle:** charging, screen and battery state transitions; precedence, hysteresis, cancellation and truthful dry-run.
3. **R04A — Module Installation Safety:** KernelSU/Magisk update staging, rollback, package integrity and release gates.
