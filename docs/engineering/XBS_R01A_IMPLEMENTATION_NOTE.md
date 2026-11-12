# XBS R01A — Runtime recovery ownership and fail-closed package safety

Source parent: `c22d1643067d9a96fe0d85404790ca9505668f4a` (PR #1, R00–R02 bootstrap). This branch/PR is stacked on the previous branch; merge PR #1 before merging R01A.

## Implemented scope

- Rollback now reports failed undo steps instead of discarding errors. It keeps unresolved original-state records in the engine snapshot for explicit retry.
- CPU governor/online-value undo is registered before privileged sysfs writes, including mutations that partially complete and return error.
- Wi-Fi, GMS and low-RAM reversible undo is registered before mutation, preserving prior-state safeguards introduced in HF01.
- Suspension is explicitly **owned** per package for Android user 0, after reading `dumpsys package <package>` and requiring an unambiguous `User 0: ... suspended=true|false` field. Pre-suspended packages are not touched or later unsuspended by XBS.
- Restore uses recorded `suspend:user0:<package>` entries, not a fresh package list or a changed allow/denylist. Failed unsuspend retains the ownership record for retry.
- `handle_apps` refuses packages protected by the built-in essentials set or the user-configured global essentials file. Failure to read configured essentials fails closed.
- Added dedicated Go regression tests for suspension ownership, malformed/ambiguous probes, list drift, rollback failure and retry, missing essentials and snapshot restoration after engine recreation.

## Honest qualification and limits

This is a **partial R01 tranche**, not complete durable transactional recovery. Engine snapshots still need durable write-ahead persistence **before** all privileged mutations (CODE-04); the daemon's current post-apply persist window remains. External package-state changes between probe and suspend, other users/profiles and cross-profile effect ownership require further reconciliation. Doze's original forced-idle state, PID reuse, fully idempotent command replay and genuine process-death restoration remain open.

The `dumpsys package <pkg>` parser intentionally refuses unknown Android output rather than guessing. API 36 physical-root coverage must confirm the actual output layout before enabling unattended package suspension.

Tests are **NOT_RUN** until `go test`/GitHub Actions executes on the applied source. Ordinary emulator tests cannot qualify privileged package operations.

## Required exact-source checks

- `go test ./internal/tuning -count=1`
- `go test ./... -count=1`
- `go vet ./...`
- `go run ./cmd/xbs contracts check`
- `go run ./cmd/xbsrepo final-seal`
- Existing GitHub Go + module job on the exact R01A commit.
- Android Gradle/emulator and real rooted integration are later, separate gates.

No Gradle execution in Termux; no root changes or emulator dispatch from this overlay. All Git operations are normal non-force operations.
