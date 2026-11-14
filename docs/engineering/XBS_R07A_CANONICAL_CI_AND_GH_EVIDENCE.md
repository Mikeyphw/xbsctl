# XBS R07A — canonical CI policy, immutable Actions and actual GitHub evidence

**Base (R06B HF05 accepted GitHub run 43554367907):** `1f646ce260f38128d409e01319d7e20db00c4368`.
**Stacked base:** `feature/xbs-r06b-native-devtool-runtime-20261113` (PR #19).
**Scope:** First consolidated R07 slice. **NOT** R07 final acceptance; R07 depends on R09 backend qualification. No physical root, QS, Android installation, Gradle in Termux or release publishing.

## Included

- R07-01: one canonical workflow; stable three mandatory PR gates, optional debug lane remains optional; no duplicate workflow spam.
- R07-02: verify all Go tests/race/vet/contracts/final seal/module; actual count and SHA-bound artifact are required.
- R07-04: replace all GitHub Actions tag references with **reviewed immutable full Git commit SHA**. Resolve annotated tags to the underlying commit. No auto-upgrade to moving major tags.
- R07-05: retain full PR Go/JVM/API36 checks even for Android-only or Go-only changes; do not allow affected selection to bypass protected dependencies.
- R07-10: no ADB/emulator in Go/JVM policy step; API36 emulator remains in its actual dedicated lane.
- R07-11/12: launcher uses native `gh pr checks` (raw visible output), observes one existing run for the exact SHA and watches it once, without dispatching duplicate CI.
- R07-13: downloaded artifact receipts and actual SHA256 bytes, run ID, attempt, APK and module digests, explicit no-physical/no-release boundaries.
- R07-14: adversarial tests for action drift, missing jobs, incomplete counts, wrong SHA/run/attempt, missing/duplicate/expired artifacts and fail-open policy.
- Existing R05C/Phase31 source policy tests are updated *only* for exact action SHA pins. All their other safety assertions and negative fixtures remain.

## Reviewed GitHub Actions pins

| Action | Immutable commit SHA | Previous major |
|---|---|---|
| actions/checkout | `11d5960a326750d5838078e36cf38b85af677262` | v4 |
| actions/setup-go | `40f1582b2485089dde7abd97c1529aa768e1baff` | v5 |
| actions/setup-java | `cf277c60eb25467037889841efdb72551f06f6c3` | v4 |
| actions/upload-artifact | `ea165f8d65b6e75b540449e92b4886f43607fa02` | v4 |
| android-actions/setup-android | `9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407` | v3 |
| gradle/actions/setup-gradle | `ed408507eac070d1f99cc633dbcf757c94c7933a` | v4 (annotated tag chain) |
| reactivecircus/android-emulator-runner | `a421e43855164a8197daf9d8d40fe71c6996bb0d` | v2 (annotated tag) |

Pins were resolved against their GitHub REST refs on 2026-11-14. Source verification does not replace hosted execution.

## Required acceptance

Run R07A overlay with GitHub CLI authenticated against the repository. The launcher must pass the **entire** real Python test inventory and fail/stop before pushing if any test fails. Its new stacked PR must run `Devtool XBS Go and Android candidates` at the exact pushed commit. Go, JVM, and API36 required jobs all conclude success; verify actual test counts from job logs, mandatory DAG 6 nodes and three affected scenarios, and download the real three artifacts. Validate run ID/attempt/SHA of Go, JVM, native-Devtool receipts, and API36 source manifest; validate module and APK SHA256 against uploaded bytes. Print real `gh pr checks` stdout; failure logs appear on rejection.

No green status is inferred from static policy, uploaded filenames, or task exit codes alone. Final R07 remains pending R09; physical/root remains pending R11.

## HF01 — job inventory scope regression (2026-11-14)

First R07A local overlay execution refused with
`JOB_INVENTORY_DRIFT android-companion-api36,android-companion-debug,android-companion-jvm,go-contract,push`.
The initial regex scanned all two-space YAML mapping keys and misclassified
`on.push` as an additional CI job. The corrected policy parses jobs strictly
within the sole top-level `jobs:` mapping and rejects missing or duplicate
sections/IDs. Synthetic self-test now includes the real push event and runs
three focused job-boundary regression cases. Real source policy and full Python
regression tests run before commit/push. **This repairs parser scope only; it
does not remove the push trigger or any mandatory job.**


## HF02 — Legacy R05A source assertion after immutable pinning

Real Termux full repository Python CI refused in `test_r05a_source_contract.py`
(`test_hosted_android_jvm_job_is_present`) because its source guard still expected
`gradle/actions/setup-gradle@v4` after R07A switched the actual workflow to
commit `ed408507eac070d1f99cc633dbcf757c94c7933a`. The source-pinned
launcher now includes and patches that exact prior-file blob (`7f274f2...`)
using the same reviewed tag-to-immutable-SHA map; a dedicated HF02 regression
suite enforces the expectation and tests wrong/missing/mutable mutants. It
also scans the seven older source-contract scripts for stale version tags.
The canonical workflow still has exactly the four intended jobs and all
three required gates; no Gradle, ADB, root or physical qualification locally.
