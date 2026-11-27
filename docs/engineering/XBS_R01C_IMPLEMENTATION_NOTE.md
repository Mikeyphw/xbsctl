# R01C — Exclusive privileged-effect ownership (conservative safety gate)

Parent SHA: `5c90596a44d8b8ce875b7e6803f3dbe9bc7fb169` (PR #3). Stacked PR against R01B.

## Implemented

- At most **one active profile with privileged side effects** because the current tuning engine owns one global original-state snapshot. Different effects on different resources still count as competing mutators: this is intentionally conservative until a genuine per-resource lease journal exists.
- Policy-only events can coexist with the mutator. Stopping one of them **never invokes global Engine.Restore**; stopping an inactive event is idempotent and cannot undo anyone's effects.
- Duplicate start is idempotent, and starting a profile while paused/safe is refused.
- Starts, reloads and reapplications (including boot recovery/resume) validate the entire proposed active set **before** any privileged mutation.
- Bulk pause/safe/shutdown/reload restoration restores the one owner only, never a policy-only profile, refuses orphaned snapshots and marks failed restoration `recovery_required`.
- Failed restoration of the mutating owner marks the daemon recovery_required and preserves pending original state in the WAL.
- Go tests exercise every privileged effect classifier and integration paths for passive stop, conflicting start, duplicate start, reload conflict, persisted two-owner rejection and failed restore.

## Explicit limitations (not qualified)

- This **does not implement simultaneous independent mutating profiles**. Correct concurrent effect sharing requires per-resource owner IDs and precedence, persistent leases, dependency-aware restoration, and fault injection across profile-order permutations. Those remain open under R01/R02 gates.
- The global effect journal is unchanged from R01B; app and process identity, Android API36 behavior, physical-root tests and emulator journeys are not qualified by this tranche.
- Original R01B queue recovery guarantees remain intact.
- Passing Go tests cannot qualify rooted-device restoration, performance, module installation, Tasker or Android UI.

## Required acceptance

`go test ./internal/daemon -count=1`, `go test ./... -count=1`, `go vet ./...`, `go run ./cmd/xbs contracts check`, `go run ./cmd/xbsrepo final-seal`; hosted workflow on exact R01C SHA, then separately Android emulator/physical rooted acceptance.
