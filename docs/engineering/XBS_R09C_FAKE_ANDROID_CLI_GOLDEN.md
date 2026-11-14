# XBS R09C — Fake Android backend and CLI golden contracts

**Stack:** `feature/xbs-r09c-fake-android-cli-20261114` on R09B
**Exact base:** `6d23d6adc9054d2ee16d42173ccb94702dc59dcf`
**Scope:** R09-01 deterministic fake backend (first complete effect-state slice) and R09-09 CLI golden/exit-code compatibility.

## Real executable Go contract

The `r09cAndroid` simulator implements `tuning.Runner`, keeping CPU governor and online state, Wi-Fi, GMS, Doze and low-RAM values entirely in memory. Its command switch refuses unknown commands, so adding an unmocked effect cannot silently become a fake success. The engine under test is the actual `tuning.Engine`, not a reimplementation.

The effect round-trip table covers CPU governor, CPU online, Wi-Fi, GMS, Doze and low-RAM. Each case creates a durable preimage, rehydrates a fresh engine, restores, and compares exact fake-Android state. Additional cases enforce external preexisting ownership, probe and write faults, WAL commit failures, and dry-run purity. No process invocation, real root, ADB, sysfs or physical system state is allowed.

The CLI golden tests execute `cli.Main`, verifying JSON schema/action/app/ok/error/exit_code for successful non-mutating commands, invalid usage, custom-event rejection, invalid configuration paths and correct durable publication of an explicitly configured custom event. They use `t.TempDir` and per-test environment overrides; no Android daemon is launched.

## Hosted qualification

The existing mandatory `go-contract` job must execute the nine exact `TestR09C*` root tests (five tuning + four CLI), with one fresh run, three repetitions, and the Go race detector. The full Go, vet, R09A fuzz, R09B fault matrix, R06B native Devtool, Android JVM and hosted API36 checks remain mandatory and unchanged. The R09C lane emits a receipt bound to the exact checkout SHA, GitHub run ID and attempt, plus the SHA256 of a real selected-tests Go coverage profile. Coverage is **R09C-selected tests only**, not a claim of full package/branch coverage.

The launcher uses a disposable Git worktree, exact upstream/Go CI workflow blob pins, a single changed-file allowlist, full repo Python precommit, an exact source-matching PR discovery and no force push. The real GitHub auditors read the latest PR workflow run and actual uploaded artifacts; no outcome is inferred from synthetic fixtures or YAML alone.

**Not complete:** R09-02 expanded schema branch matrix, R09-04 exhaustive daemon crash points, R09-06 ongoing fuzz corpus, R09-10 risk-weighted full branch coverage and R09-11 separate opt-in physical root qualification. R09C and all hosted checks cannot certify rooted hardware safety or release readiness.
