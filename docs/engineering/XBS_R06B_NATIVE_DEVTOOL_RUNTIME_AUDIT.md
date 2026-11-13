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
