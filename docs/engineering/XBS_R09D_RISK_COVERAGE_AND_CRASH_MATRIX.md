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

## HF01 — Hosted skip classification (2026-11-14)

The original R09D full Go event parser rejected **all** skip events, blocking
GitHub run `43641941925` despite the existing code intentionally requiring
separate permission/approval for two tests. HF01 preserves the complete
full-suite test execution and strictly allowlists exactly these root-level,
source-documented exceptions **on non-root hosted Linux only**:

- `internal/control::TestR02AuditedSpoolRejectsForeignOwnerWhenSupported` — cannot create a foreign-owned directory without root.
- `internal/tuning::TestRootIntegrationEffectFamilies` — disabled without the deliberate disposable-root-device acknowledgement.

Every other skipped test, missing expected skip, changed skip reason, duplicate
skip, failed test or missing risk-package execution refuses qualification. The
receipt records both skipped IDs, a count of two, zero unexpected skips and
zero failed tests; the hosted log marker records the count separately. They
**do not count as executed test cases** and do not imply physical root/device
qualification. The R09D statement-coverage gaps remain independently visible.

HF01 updates the existing R09D PR only, preserving R09A–C CI contracts.

## HF02 — Package-level no-test terminals are not skipped tests

GitHub run `43643065801` failed because Go 1.23's `-json` full-suite output
contained a package-level `skip` for `internal/version` with no `Test` field.
This can legitimately mean `[no test files]`, unlike a skipped *test*.

HF02 separates these outcomes without weakening the real-test gate:

- `internal/machine` and `internal/version` are the only permitted packages with no `*_test.go` source; hosted validation confirms both directories have Go source and no test files.
- Either package may emit a single `pass` or a single `skip`; a skipped package must include Go's exact `[no test files]` diagnostic for that package.
- All required seven risk packages must still have actual passing root tests, and the two environment-gated root-only test skips retain exact identity and reason validation.
- A skipped package is **not** counted as an executed test or permission to ignore arbitrary skips. Missing, duplicated, inconsistent and forged no-test outcomes refuse acceptance.
- The source-bound coverage receipt records each no-test package's terminal status, and the CI log includes the number of package-only skips independently of the two root-only test skips. This is not physical root or release qualification.

HF02 updates PR #24 by a normal fast-forward commit, preserving all prior
R07A–R09D security and Android gates.

## HF03 — Cross-package Go coverage is a union, not concatenated lines

Run `43645197959` on PR #24 at `810d1484d0ff130da0e36d68ec79c2bf677b70bb`
reached the R09D collector but refused `COVERPROFILE_DUPLICATE_BLOCK`. The
command `go test -covermode=atomic -coverpkg=./internal/... -coverprofile=... ./internal/...`
builds separate per-package test binaries with overlapping instrumented
sources. Go legitimately writes the same file/coordinate block repeatedly,
including with different hit counts.

HF03 indexes each profile block by **source path and exact start/end
coordinates**, requires every observation of that block to report the same
statement count, and computes covered state as a boolean union of the observed
hit counts. Each unique block contributes exactly once to the denominator.
Contradictory counts, invalid spans, foreign-source blocks, missing risk
packages, and zero-coverage risk packages remain hard refusals.

The source-bound coverage receipt includes `coverage_unique_blocks` and
`coverage_repeated_observations`; the independent GitHub log auditor binds the
reported merged observation count to those actual archived profile bytes.
This prevents false inflation of tested-statement denominators from repeated
instrumentation. Below-target packages remain explicitly unqualified.

The repair changes no Go production code, Android operations, workflow trigger
or release policy. Hosted Go/JVM/API36 acceptance is still mandatory.
