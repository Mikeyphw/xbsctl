# R01D — Distinct-domain effect leases and hosted CI registration tolerance

Source parent: `5b6c812fcb9c8c3f244238a02abf2956903deba4` (PR #4, R01C). Stack this PR on PR #4.

## Delivered

- Canonical, sorted resource leases for CPU governor, CPU online/offline, package actions, process nice, GMS, low-RAM, Doze and Wi-Fi.
- Non-overlapping effect-domain owners can run simultaneously. Same-domain conflicts fail before privileged mutation.
- `RestoreResources` restores only one owner's recorded reversible state; other owners' WAL entries remain untouched and durable.
- Pause/safe/shutdown/reload intentionally restore all owned resources **once**, then reload/resume may reapply the validated complete set.
- Durable `resource_leases` in the v1 runtime WAL detect configuration drift before restart reconciliation.
- Failed owner restore enters `recovery_required`; successful retry requires explicit recovery.
- New Go fault-injection tests for independent lease start/stop in both orders, partial restoration, bulk transitions, and WAL drift.
- Embedded Python launcher polls GitHub Actions by exact pushed SHA: registration grace 120s, interval 10s, total 600s, checks go-contract job, records skipped Android separately, and diagnoses failed jobs.

## Safety limitations

The leases are **per domain**, not per individual process/CPU/package. Two CPU-governor mutators still conflict even on different cores, intentionally. `kill` actions are irreversible; this overlay only tracks reversible states. PID reuse is not yet addressed. Physical API36/root and hosted Android emulator evidence are separate, not passed by Go CI. A config change while active requires a reload; out-of-band drift rejects startup rather than guessing. Persisted WAL corruption is not auto-repaired. No local Android Gradle or root changes from overlay.

## Required release gates

1. `go test ./... -count=1`, `go vet ./...`, contracts, final seal on exact resulting SHA.
2. One GitHub Actions run with matching pushed SHA, `go-contract` success, and module receipt.
3. Later opt-in rooted-device confirmation of physical reversible effect domains; hosted API36 emulator app journeys separately.

## Next overlays

1. R01E — PID identity and per-domain restoration/adversarial hardening.
2. R02A — Queue path security, interruption reconciliation, daemon restart fault injection.
3. R03A — Automatic event observation and precedence with dry-run/charge safety.
