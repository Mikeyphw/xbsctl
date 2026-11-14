# XBS R09D — crash boundaries and risk package coverage inventory

Source basis: `ffe8bc2c2efb28126e57d973dee7620245fb47e9` on `feature/xbs-r09c-fake-android-cli-20261114`, PR #23. R09C's live run #43616766849 had successful Go/JVM/API36 jobs. This is the next **hosted-only** suite; not proof of physical root safety or final release readiness.

## Concrete R09 coverage

- R09-03/R09-04: `TestR09DPostEffectCrashRecoveryMatrix` simulates six panic-after-mutation points (CPU governor, core online, GMS, low_ram, Doze, Wi-Fi) on the existing memory-backed R09C Runner. A reconstructed engine must restore all earlier effects solely from its journal. Snapshot aliasing and failed restore/release retention also have named tests.
- R09-05: `TestR09DQueueClaimAckCrashMatrix` and `TestR09DImmutableAckRejectsCompetingCompletion` prove the durable queue never replays an interrupted or acknowledged command, even if cleanup did not occur.
- R09-08: all **six root test IDs** must pass one fresh execution, three repeat executions, and a race-enabled execution in the Go CI job; subtests do not substitute for missing root IDs.
- R09-10: `go test -json -count=1 -covermode=atomic -coverpkg=./internal/... -coverprofile=dist/r09d-risk.coverprofile ./internal/...` measures **full internal-package** statement coverage rather than R09A's selected-test-only coverage. Count all actual root test pass events. Compute separate statement-weighted coverage for `cli`, `config`, `control`, `daemon`, `eventbridge`, `module`, `tuning`. Each risk package must have positive exercised statements, recorded counts and a full-suite package pass. Coverage profile is independently verified against its SHA-256 digest on GitHub.
- `target_pct=50` per risk package is a **future R09 completion target**, not an automatic pass. The receipt lists all `unqualified_risk_targets` and sets `r09_full_coverage_gate_qualified` accurately; a green R09D workflow does **not** imply meeting all target thresholds.
- R09-11: root integration remains separated. No Android device, root privileges, module installation, Gradle or release signing is executed by the R09D test or launcher.

## Required outputs

The existing `go-contract` required job runs `R09D crash recovery and complete risk coverage inventory`, after the R09A/B/C gates. The module-candidate artifact contains `dist/r09d-risk-coverage.json` and `dist/r09d-risk.coverprofile`. These bind actual test counts, source commit, `github_run_id`, `github_run_attempt`, risk coverage ledger and SHA-256 of raw coverage data. Independent `r09d_gh_evidence.py` checks the exact PR run, Go step and other required jobs, downloads the actual artifact and refuses source/count/hash drift.

## Still open

R09's final comprehensive gate requires review and improvements of any below-target coverage package, more randomized crash/fault patterns, fuzz target expansion, cross-process daemon lifecycle tests, and separate opt-in hardware root qualification under R11. R07 policy optimization remains dependent on completed R09. No published module is installed or released as part of this overlay.
