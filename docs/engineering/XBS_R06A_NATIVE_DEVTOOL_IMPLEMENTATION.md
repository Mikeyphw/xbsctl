# XBS R06A — native Go Devtool DAG and Android artifact boundary

**Implementation base:** `133f1923bb4ea501ed8caf074d5dac30464fbecb` — R05C hosted `43495194055` green (Go, Android JVM, API36 instrumentation, checksum-verified APK). Separate stacked PR against the R05C branch; no R05C branch mutation.

**Upstream reviewed Devtool source reference:** `Mikeyphw/devtool` `44eb17bbbd63194aa67cde0f7b63d5ada44fb96e`. Native `[jobs.*] runner="go" action="test"|"lint"` and explicit DAG semantics were checked against `docs/POLYGLOT_TARGETS_AND_WORKFLOWS.md` and `devtool/project_runners.py` at this commit. This is a **source review pin**, *not* proof of installed Devtool wheel identity. Do not claim that R06-07 is complete until the installed interpreter/module and exact wheel hashes are verified in the destination environment.

## Delivered in R06A

- `R06-01` native Go **test** and **vet** DAG job actions. Devtool's GoRunner uses `go test -json` and `go vet` and retains structured test output.
- `R06-02` project-specific `xbs contracts check`, `xbsrepo final-seal`, and `build-module` stay explicit commands, rather than trying to replace them with generic Go package commands.
- `R06-03` correct target-root-relative Android inputs, outputs and debug APK artifact path. Require the APK to exist and pass Devtool generic artifact verification for an Android build; it is **not** proof of signing authority or physical acceptance.
- `R06-04/05` explicit ordered module DAG, preserving Go test and vet independence; both must pass before contracts and final seal, and a module cannot be built without passing final seal through the reviewed Devtool `build` or `ci-module` workflow.
- `R06-06` extend `VerifyRepositoryHandoff` to accept the native Go runner while retaining legacy compatibility; new Go tests prove missing test, vet and seal refuse.
- `R06-12` this overlay executes no local Gradle. The existing GitHub-only Android JVM and API36 mandatory jobs are left unchanged, including the Linux Go race detector. This is an overlay policy; no claim that arbitrary user invocation of the Devtool Android target is physically impossible.

## Still open for full R06

- `R06-07` deployed Devtool runner/wheel exact bytes and toolchain identity.
- `R06-08/09` actual Devtool CLI `plan --explain`, `preflight --json`, affected target selection and unknown-owner conservative fallback at the source pinned runtime, compared to full validation.
- `R06-10/11` external handoff receipt provenance and idempotent Actions dispatch/resume integration on a live GitHub run.
- Independent hosted execution must pass all three mandatory jobs on the new R06A commit. Do not equate offline source tests to native runner or hardware qualification.

## Termux use

Run the R06A Python ZIP launcher from a clean R05C head. Gradle remains GitHub-hosted only. The launcher creates a separate stacked branch and PR, runs all repository Python checks and Go checks, and handles failed precommit safely. `$TMPDIR` is used for disposable test checkouts. `--check` is nonmutating.
