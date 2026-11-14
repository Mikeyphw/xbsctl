# XBS R06B — Hosted native Devtool runtime and affected-target qualification

**Base:** R06A stacked PR #18, exact commit `b87d1bf18fd20b18c4d76d4c9b8890af24e9a104`. Prior hosted GitHub run `43500262306` succeeded (required Go, Android JVM and API36 emulator). **Source dependency:** reviewed `Mikeyphw/devtool` commit `44eb17bbbd63194aa67cde0f7b63d5ada44fb96e`, version `1.1.0`. No user hardware is touched.

## Delivered

- `R06-07`: Hosted Linux job fetches the exact reviewed Git commit, builds one local wheel, records SHA-256, installs it without fetching another Devtool build, verifies import origin, distribution version and checkout SHA. Runtime dependencies (`rich`, `PyYAML`, `setuptools`, `wheel`) have fixed package versions; their downloaded wheels are **not** claimed independently hash-pinned yet.
- `R06-08`: `xbs` target switches to `affected_ownership="inputs-only"` so its repository-root execution does not claim every Android/unknown file. A disposable Git test expects shared Go change → Go+Android; Android-only change → Android; unknown nonowned file → both (conservative fallback). This is actual upstream Devtool affected analysis, not self-attested TOML.
- `R06-09`: Pinned installed Devtool executes actual `plan ci-module --target xbs --json`, `preflight --target xbs --workflow ci-module --json`, and human `plan --explain`. The gate refuses missing/duplicate nodes, dependency bypass, missing test/vet/contracts/seal/module, commands invoking Gradle/root, non-native Go test/vet, unhealthy preflight, and invalid target selection.
- `R06-10`: Workflow uploads `dist/r06b-devtool-qualification.json` with exact source SHA, GitHub run id/attempt, Devtool checkout SHA, wheel SHA-256, native DAG and affected count; receipt explicitly refuses hardware/release qualification.
- `R06-11`: Overlay watcher reuses the actual SHA-bound GitHub Actions run and requires the new named Devtool step, the Go job, Android JVM and API36 all to be successful. It does **not** dispatch duplicate runs or mutate a succeeded run.
- `R06-12`: No Termux/hosted-local Android Gradle is introduced. The existing Android JVM/API36 hosted actions, required Go Linux race, instrumentation case count and SHA-bound APK publishing remain unchanged.

## Qualification boundaries

1. This overlay's offline fixtures test schema/refusal and rollback; they **cannot certify that native Devtool actually executes**. The new GitHub workflow is the real producer gate.
2. The new job uses dynamic network downloads of **fixed-version, nonhash-locked** Python wheel dependencies. Full supply-chain reproduction remains an explicit open review item, not an automatic release signoff.
3. The R06B runtime receipt is not a KernelSU/Magisk module installer, signed release, screenshot, physical-device Root/Tasker proof, or R07 full CI parity result.
4. `R06-11` only verifies safe non-duplicating CI observation on an existing PR. Broader Actions journal/resume governance and R07 optimization must be separately qualified.
5. Only the five declared source paths may be committed. No secret tokens, raw logs, device state or sensitive data are uploaded.

## R06B HF01–HF03: strict native JSON and measured test counts

- HF01: The source-pinned Devtool 1.1.0 universal JSON response uses an **outer schema-1 envelope**; native `plan` and `preflight` use **schema 2**, while `affected` uses **schema 1**. The hosted check now validates both layers, actual commands and native `ci-module` action without weakening DAG, root or artifact gates.
- HF02: `scripts/ci/fixtures/r06b_devtool_envelopes.json` contains source-derived real wire-format envelopes from `devtool/api_output.py` (pinned at `44eb17bbbd63194aa67cde0f7b63d5ada44fb96e`); the data values are deterministic contract fixtures, not fake claims of a hosted run. Negative tests reject outer/native schema drift, false `ok`, incorrect exit type, wrong command, missing native data, unwrapped payloads and native action drift. The actual live envelopes are SHA-256 attested in the hosted receipt, including both schema identities. No sensitive raw output is uploaded.
- HF03: Hosted Go case counts come from **actual** `go test -json -count=1 ./...` pass events, rejecting failures, skips, duplicates, missing package pass and zero cases. Hosted Android JVM counts come from **actual Gradle JUnit XML**; missing/skipped/failed/zero-case results refuse. Both receipts are SHA/run/attempt bound. The Go counts hash is embedded in the R06B receipt. Existing R05C Python complete-suite count and API36 actual instrumentation class counts (16 minimum) remain mandatory. Rootless API36 still depends on Go and Android JVM jobs. Gradle runs ONLY inside GitHub Actions.
- Qualification remains pending until the new PR-head GitHub run proves `go-contract`, `android-companion-jvm` and `android-companion-api36` successful with actual count receipts and SHA-bound artifacts. No physical root, Tasker device sign-off, or release qualification is implied.

## R06B HF04 — hosted Go skip provenance and Android JVM XML variant reconciliation

Audited source SHA: `024e906a46a004729b5c3e48dedd54c32dbfc095`.
Failed real hosted run `43551437208`:
- `go-contract`: `GO_TEST_SKIPPED 2`, after Go unit, race and vet succeeded. The two reviewed intentional Linux-host exemptions are `TestRootIntegrationEffectFamilies` (root/device acknowledgment required) and `TestR02AuditedSpoolRejectsForeignOwnerWhenSupported` (requires privileged chown to a foreign owner). The new parser allows **only** exact source-owned names and reason substrings, counts them as skips, never passes, and refuses any unknown/changed skip, failure, duplicate, empty pass suite or missing package receipts. Live receipt lists package/test/reason contract and totals; no raw logs.
- `android-companion-jvm`: `JVM_RESULTS_DIRECTORY_MISSING` despite successful Gradle tasks. Android baseline profile configuration uses `testBuildType (... ? 'debug' : 'release')` and the log showed `testReleaseUnitTest` as well as `testDebugUnitTest`. The reader now inspects both named task output directories, **requires actual JUnit TEST-*.xml and positive case counts**, validates failed/skipped/duplicates for each present variant, prefers debug only when XML exists, otherwise records the real release variant; still refuses absent or zero-case evidence. No fake XML or Gradle success substitution.
- The existing R06B schema-1 SHA/run/attempt count receipts remain mandatory. New fields expose `approved_skips`, `unexpected_skipped_cases`, `selected_variant` and `available_variants`; the host verifier accepts only the two reviewed exemptions and still refuses unapproved skipped Go/JVM cases. API36 remains a mandatory dependent GitHub Actions gate and requires at least 16 real instrumentation cases.
- `scripts/ci/test_r06b_hf04_count_gates.py` adds focused regression/negative tests and is automatically discovered by the complete repository Python test suite. No local Gradle/adb/root or physical qualification.
