# XBS R03A — Opt-in automatic power/display event transitions

Base: PR #8 commit `9dfc6846b45d32566c9df79a847ad7966c9c3246`. New branch: `feature/xbs-r03a-automatic-event-lifecycle-20261112`.

## Implemented source (not yet Android-qualified)

- A read-only probe of `/sys/class/power_supply/battery/status`, `/sys/class/power_supply/battery/capacity`, and `dumpsys power` `mWakefulness` (exact recognized values only). Missing/ambiguous output never generates a privileged command.
- A two-sample, `header.delay`-bounded deterministic planner with low-battery thresholds and hysteresis. Candidate priority is **charging > screen_off > low_power**, one managed automatic profile at once. This deliberately sacrifices independent multi-event combinations in R03A.
- `header.automatic_events_enabled` is **false by default**. Explicit opt-in requires `xbs config set header.automatic_events_enabled true` followed by confirmed daemon reload. `header.low_power_threshold=20`, `header.low_power_release_threshold=25` are defaults; `header.delay` governs additional stabilization, default 3 seconds.
- Automatic start/stop requests use exactly the audited R02 durable queue with an `origin=automatic` marker. The daemon validates that origin before any mutation, records automatic ownership with its active-state WAL, and refuses an automatic stop of a manually started profile. A manual `start` or `stop` takes ownership back from the automatic subsystem.
- Status separates observed candidates from `active_events` (applied state); `automatic_owned` is persisted with the runtime journal for recovery. On observation failure, no new command is queued. The scheduler halts on a rejected automatic receipt until explicit reload.
- CLI `--dry-run` and `header.dry_run` both select dry-run; no Gradle runs in Termux. `--once` never triggers automatic polling.

## Deliberately deferred to R03B / R03C

- Late-start **boot once** application and the `quit` policy (existing `boot` command remains usable manually).
- `keep_on_charge` precedence, independent overlapping automatic profiles, advanced device-specific display probes, restarting with a pending unclaimed auto request, and lifecycle execution after the app dies.
- Exact API36 Android root physical acceptance, real charging/display broadcasts, on-device energy impact, long-term recovery receipts.

## Required qualification

1. Embedded ZIP self-tests on every bundled standalone control and autoevents Go test, plus stubbed daemon integration tests, with Linux race/vet and Android ARM64 compile-only where supported.
2. On the actual source-pinned Termux checkout: targeted `go test ./internal/autoevents ./internal/control ./internal/daemon`, `go test ./...`, vet, contracts and final seal, before commit.
3. Hosted GitHub Actions `go-contract` exact-SHA success; skipped Android job remains unqualified.
4. Physical Android runtime boot/sensor/ack tests before classifying R03 fully accepted.
