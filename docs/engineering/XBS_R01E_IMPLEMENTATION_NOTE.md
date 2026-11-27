# XBS R01E — Process identity and restoration hardening

**Source:** `ce49ec0a8d0cd12f737892ce7817bd31018161b5` (R01D, PR #5). This overlay makes a stacked PR on that branch.

## Implementation

- Niceness WAL keys are now `nice:<pid>@<startticks>`, using kernel /proc/PID/stat field 22. Reads handle process names containing parentheses/spaces and reject malformed or zero starttimes.
- Restore verifies the process still has that starttime. If it exited or the PID was reused, no renice occurs; the obsolete record is released only after durability succeeds. Unreadable ambiguous process state retains a recovery record and fails closed.
- Legacy `nice:<pid>` records do not trigger blind renice of potentially reused PIDs. They require explicit safe reconciliation.
- `handle_gms=kill` checks exact package membership in both enabled and disabled `pm list packages` output; contradictory/unknown state refuses mutation. This prevents substring collisions with similarly named packages.
- Wi-Fi initial state requires a known, explicit `Wi-Fi is enabled/disabled` first-line result; negative/unknown wording fails closed.
- Doze probes `dumpsys deviceidle get force`. Pre-forced state belongs to an external actor; XBS neither forces nor unforces it. Unknown force status refuses the effect. Android's DeviceIdleController shell contract exposes `get force` as a boolean.
- Adds Go regression tests covering reused PID, exited PID, legacy snapshot, malformed `/proc`, GMS suffix collision, state ambiguity, Doze external ownership and Wi-Fi negation.

## Boundaries / unresolved risks

- A PID can still change between the last `/proc` check and a command-line `renice -p PID` call; only an identity-bound kernel operation (e.g. supported pidfd path) would close that final race. This implementation narrows but does not prove away the TOCTOU gap.
- Effect-domain leasing still forbids two profiles claiming the same domain. Per-core/per-package shared leases remain separate work.
- On legacy PID-only journal data, privileged restoration is refused rather than guessing. Manual recovery instructions must be developed for device deployment.
- GMS `pm` outputs and DeviceIdleController `get force` must be validated against the exact physical Android 16 device before unattended use.
- Rooted device/emulator/Android Companion qualification is NOT performed by this overlay. Hosted Go tests qualify only their own backend lane.

## Required gates

- `go test ./internal/tuning ./internal/daemon ./internal/control -count=1`
- `go test ./... -count=1`
- `go vet ./...`
- `go run ./cmd/xbs contracts check`
- `go run ./cmd/xbsrepo final-seal`
- SHA-pinned GitHub Actions `go-contract` job, not the skipped Android job.

## Next three overlays
1. R02A — queue and daemon security/recovery hardening
2. R03A — automatic battery and screen event lifecycle
3. R04A — module installation and artifact safety

## HF01: Daemon fixture compatibility and durable-order regressions
- Model `dumpsys deviceidle get force` explicitly in the daemon synthetic runner; preserve injected failures.
- Add daemon integration tests for probe-before-WAL-before-force ordering, external ownership, and probe error refusal.
- The complete patched repository suite is a mandatory precommit gate, not pre-qualified by these offline tests.
