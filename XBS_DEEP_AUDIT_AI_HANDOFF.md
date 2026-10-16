# XBS Go Runtime and Android Companion Deep Audit

## Audience and objective

This document is an implementation handoff for an AI coding agent. Its purpose is to drive a safe, test-backed remediation of confirmed defects in the XBS Go module runtime and Android companion application.

Treat the findings below as independently reproducible defects, not as permission to redesign unrelated surfaces. Preserve the frozen CLI, compatibility, configuration, and Tasker contracts unless a fix explicitly requires a versioned contract change.

## Repository scope

- Go runtime: `apps/xbs/cmd` and `apps/xbs/internal`
- Module launchers and payload: `apps/xbs/service.sh`, `apps/xbs/system`, and `apps/xbs/module`
- Android app: `apps/xbs/android-app`
- Devtool targets: `xbs` and `xbs_android` in the repository `.devtool.toml`

## Executive summary

The Go code passes its current tests and vet checks, and the Android main application builds and passes lint. Those checks do not establish functional correctness.

The highest-risk defect is architectural: the production Go daemon accepts event commands and publishes state but never applies any of the XBS tuning configuration. It therefore reports successful profile activation without changing application handling, CPU cores, Doze, Wi-Fi, low-RAM state, or process behavior.

The daemon also publishes every genuine transition and then immediately replaces it with a second envelope whose `last_change` is null. This can prevent Android and Tasker from ever observing the transition.

The Android side contains two root-process lifecycle defects, an API-level compatibility failure in Tasker integration, synchronous Room access from main-thread paths, and a test source file that does not compile.

## Required remediation order

1. Restore a green Android unit-test compilation gate.
2. Prevent the daemon from erasing transition records.
3. Decide and implement the production tuning engine contract. Do not continue presenting event activation as applied until the requested effects are actually applied or explicitly reported as unsupported.
4. Make root transactions and foreground commands process-death and timeout safe.
5. Repair Tasker behavior across the declared API range or explicitly raise the supported API level/disable unsupported integration.
6. Fix reload, control transport, custom-event CLI parity, strict configuration validation, and Room threading.
7. Add regression tests for every fixed defect before refactoring adjacent code.

## Findings

### XBS-01 — Critical — Production daemon does not apply XBS tuning

Evidence:

- `internal/daemon/daemon.go:103-156` parses `start`, `stop`, `reload`, `pause`, `safe`, and `resume`, then only mutates an in-memory `state`.
- `internal/daemon/daemon.go:159-193` writes status and event-bridge JSON.
- Repository-wide source search finds runtime fields such as `handle_apps`, `handle_gms`, `handle_cores`, `disable_cores`, `doze`, `kill_wifi`, `low_ram`, `allowlist`, `denylist`, and `proc_file` only in validation, schema, tests, compatibility data, and Android editing code—not in a Go execution engine.
- `service.sh:12-14` launches this Go daemon as the production boot daemon.

Impact:

- `start <event>` can report success and mark the profile active while applying no battery or performance behavior.
- Android status, notifications, and Tasker can all present a false-success state.
- Configuration exposed by the app is effectively inert.

Required fix:

- Implement a typed event-resolution and effect engine that merges template/event settings, loads bounded rule files, applies effects, and records per-effect outcomes.
- Implement symmetric restoration for stop, safe mode, pause/resume, daemon shutdown/restart, and partially failed application.
- Make state publication reflect committed effects, not merely accepted commands.
- If full effect parity is intentionally deferred, fail closed with an explicit unsupported result instead of reporting the event active.

Required tests:

- Unit tests for template inheritance and every configuration field.
- Tests proving no active state is published when effect application fails.
- Tests for partial failure and rollback/restoration.
- Android-facing status fixtures showing applied, failed, and partially restored outcomes.
- Device/root integration tests for each supported effect family.

### XBS-02 — Critical — Genuine transitions are immediately overwritten

Evidence:

- `internal/daemon/daemon.go:156` calls `writeEventChanges(cfg, s, change)` with the genuine transition.
- Both caller paths then call `writeEventChanges(cfg, s, nil)` at lines 56 and 68.
- `writeEventChanges` stores its third argument directly as `last_change` at line 190 and republishes the authenticated bridge.

Impact:

- The durable latest event becomes `last_change: null` immediately after every command.
- Android polling can observe only the second sequence and miss the real event.
- Tasker event conditions and transition notifications become timing-dependent.

Required fix:

- Give one layer sole ownership of event publication.
- Prefer returning the transition from `processCtl`, then write status and publish exactly once in the caller.
- Do not emit a second sequence unless there is a distinct semantic event.

Required tests:

- After each command, assert exactly one sequence increment.
- Assert that the final `event_changes.json` and authenticated envelope retain the expected `last_change`.
- Cover start, stop, reload, pause, resume, safe, one-shot mode, and the normal loop.

### XBS-03 — High — Android transaction timeout does not terminate the root process tree

Evidence:

- `XbsRootApi.java:88-115` starts `su -c <shell>`.
- On timeout, lines 105-107 destroy only the Java `Process` representing `su`.
- `XbsConfigTransaction.java:89-114` runs execute, rollback, and recovery through this helper with 75-second timeouts.
- `RootCommandRunner` already contains a process-group/PID-file strategy, but `XbsRootApi` does not use it.

Impact:

- Android can return timeout/failure while a privileged transaction continues.
- A late commit or rollback can race with a subsequent user action.
- Transaction locks and incomplete journal state can remain behind.

Required fix:

- Consolidate privileged execution behind one cancellable process-tree runner.
- Use a unique root-side PID file and process group, and verify termination before returning timeout.
- Ensure TERM/INT reaches the transaction shell so its EXIT rollback trap executes.
- After timeout, query transaction state and return a truthful result such as rolled back, still running, or recovery required.

Required tests:

- A deliberately blocking transaction child must not survive timeout.
- Timeout during staging and promotion must produce the correct journal state.
- A second transaction must not race a timed-out predecessor.

### XBS-04 — High — Foreground commands are stored only in static memory

Evidence:

- `RootCommandService.java:21-22` defines static `PENDING` and `ACTIVE` maps.
- `start()` stores the complete command in `PENDING` at lines 32-35.
- The service intent contains only `runId` at lines 36-38.
- `onStartCommand()` silently returns when `PENDING.remove(runId)` is null at lines 80-84.
- `RunStore.started()` has already persisted the record as `running` before service startup.

Impact:

- Process recreation between service scheduling and delivery loses the command.
- The history row remains active until stale reconciliation.
- Because the missing-command branch does not promote the service, it can violate the foreground-service startup deadline.

Required fix:

- Persist a bounded, authenticated/validated pending-command envelope before starting the service, or place a safe parcelable specification in the explicit intent.
- Revalidate the recovered specification against the current allowlist.
- Finalize missing or corrupt pending commands as failed and promote/stop the foreground service correctly.
- Remove pending data only after ownership is durably transferred to the active execution record.

Required tests:

- Recreate the service with empty static maps and a persisted pending record.
- Verify missing/corrupt pending state becomes a terminal failure.
- Verify no run remains indefinitely active.

### XBS-05 — High — Tasker execution is rejected on API 26–33

Evidence:

- The Android app declares `minSdk 26` in `app/build.gradle`.
- `TaskerConfigCodec.sentFromPackage()` and `sentFromUid()` return unavailable values below API 34 at lines 59-68.
- `TaskerConfigCodec.senderMatches()` returns false when both values are unavailable at lines 70-76.
- Both exported Tasker receivers require `senderMatches()` after HMAC and installed-certificate validation.

Impact:

- Tasker actions and conditions cannot execute on Android 8 through Android 13.
- Configuration editing may succeed, but runtime delivery fails closed every time.

Required fix:

- Define an API-specific sender-authentication strategy.
- On older APIs, rely only on an approach that is actually available and defensible, such as an explicit package-scoped callback/protocol plus the existing app-generated HMAC and pinned installed certificate.
- If equivalent assurance cannot be provided, expose the limitation in UI/capabilities and stop advertising Tasker runtime support on those API levels.

Required tests:

- Robolectric or instrumentation coverage for API 26, 28, 33, 34, and current target.
- Valid signed requests, spoofed packages, changed certificates, missing pins, and replay attempts.

### XBS-06 — High — Android unit-test source does not compile

Evidence:

- `app/src/test/java/io/github/mikeceead/xbscompanion/XbsCompatibilityReportTest.java:99-103` contains JSON with missing Java escaping.
- Devtool reports 46 Java compilation errors starting at line 99.

Required fix:

- Correct the test string using properly escaped concatenation, a text block compatible with the configured toolchain, or programmatic `JSONObject` construction.

Required test:

- Run both debug and release unit-test tasks after correction.

### XBS-07 — Medium — Reload reports success without reloading configuration

Evidence:

- `internal/daemon/daemon.go:41` loads configuration once.
- The long-running loop passes the same map to every `processCtl` call.
- The reload branch at lines 140-142 only updates `LastReloadTime`.

Impact:

- Added/removed events, field changes, log levels, and path changes are ignored until process restart.

Required fix:

- Load and validate a fresh configuration into a temporary value.
- Atomically swap it into the daemon only after validation succeeds.
- Preserve the previous configuration and publish a failed reload result on error.
- Reconcile currently active events against the new configuration.

### XBS-08 — Medium — Single control file has read/clear and writer races

Evidence:

- `internal/control/control.go:210-214` writes directly to the shared file with `os.WriteFile`.
- `internal/daemon/daemon.go:103-118` reads the file and subsequently clears it with another write.

Failure sequence:

1. Daemon reads command A.
2. Client writes command B.
3. Daemon clears the file.
4. Command B is lost.

Concurrent clients can also overwrite one another before the daemon polls.

Required fix:

- Replace the single mutable slot with an atomic inbox protocol: uniquely named command files plus atomic rename, a locked append-only queue, or a request/ack directory.
- Include request IDs so `--wait` observes completion of its own command rather than coincidental state.

Required tests:

- Deterministic read/write/clear race reproduction.
- Multiple concurrent producers.
- Ordered processing and per-request acknowledgement.

### XBS-09 — Medium — Primary CLI rejects configured custom events

Evidence:

- `internal/cli/cli.go:23` hardcodes only built-in event names.
- `event run` and `event stop` reject values not in that map at lines 227-240.
- `internal/control` and `internal/daemon` otherwise support event names found in the loaded configuration.

Required fix:

- Validate event names against the loaded configuration, including the built-in `manual` behavior.
- Apply a shared event-name grammar across Go, Android, and configuration validation.

### XBS-10 — Medium — Required JSON paths accept null and non-string values

Evidence:

- `internal/config/config.go:299-303` applies `fmt.Sprint` to required path fields.
- JSON null formats as `<nil>`, which is non-empty and passes validation.
- Downstream `fmt.Sprint` calls can then interpret `<nil>` as a literal path.

Required fix:

- Require actual string values for `log_file`, `ctl_file`, `lock_file`, and `essentials_allowlist`.
- Reject null, non-string, empty, whitespace-only, NUL-containing, and otherwise invalid paths.
- Consider applying the existing symlink/component policy consistently to runtime control/status paths.

### XBS-11 — Medium — Room DAO calls occur synchronously on main-thread paths

Evidence:

- `XbsHistoryStore` exposes synchronous methods such as `allRuns()` and `upsertRun()`.
- Room is not configured with `allowMainThreadQueries()`.
- `RootCommandService.ensureForeground()` calls `RunStore.active()`, which reaches `allRuns()`, from service lifecycle code on the main thread.
- `XbsHistoryStore` catches the resulting `RuntimeException` and silently falls back to emergency/legacy storage.

Impact:

- Main-thread history access cannot use Room reliably.
- Failures are masked as fallback behavior, making persistence appear healthy when it is not.
- Main-thread fallback parsing can still add latency.

Required fix:

- Move all Room operations to the database executor and expose asynchronous callbacks/futures or cached read models to UI/service code.
- Do not use broad `RuntimeException` fallback as the normal main-thread path.
- Keep emergency storage for actual database failure, with explicit diagnostics.

## Additional correctness concerns to cover while fixing

These are lower-confidence or lower-severity observations. Validate them before changing behavior:

- `--dry-run` status always publishes `detail.dry_run: false`, and a long-running dry-run daemon repeatedly reprocesses the uncleared command.
- Successful processing does not clear `LastError` or `LastErrorTime`, so stale errors may remain visible indefinitely.
- A daemon restart initializes mode to normal and active events to empty rather than reconciling persisted status or actual applied system state.
- Go and Android accept event object keys more broadly than the editor's event-name grammar. Define one canonical grammar before enabling arbitrary custom-event CLI lookup.
- Event-bridge default status paths and configuration-resolved status paths can diverge when environment overrides are used.
- Devtool emits extensive Termux `aapt2` resource-ID diagnostics even though build and lint succeed. Treat this as toolchain noise unless a packaged-resource failure is reproduced.

## Validation evidence from this audit

Commands already run:

```sh
cd apps/xbs
go test ./...
go vet ./...

cd ../..
devtool --target xbs_android --no-hud --stdout-payload raw build
devtool --target xbs_android --no-hud --stdout-payload summary lint
devtool --target xbs_android --no-hud --stdout-payload raw test
```

Observed results:

- Go tests: pass.
- Go vet: pass.
- Android debug APK build: pass.
- Android debug lint: pass with zero reported lint warnings/errors.
- Android unit-test task: fails during test-source compilation because of XBS-06.
- Android instrumentation tests were not run.

Devtool generated an ignored `apps/xbs/android-app/local.properties` and build/artifact outputs. Do not commit generated SDK paths or build artifacts.

## Expected validation after remediation

Run the repository-managed workflows rather than invoking Gradle directly:

```sh
devtool --target xbs test
devtool --target xbs build
devtool --target xbs_android test
devtool --target xbs_android lint
devtool --target xbs_android build
devtool --target xbs_android --variant release test
```

Also run targeted race tests for Go code:

```sh
cd apps/xbs
go test -race ./internal/control ./internal/daemon ./internal/eventbridge
```

Instrumentation should cover service process recreation, foreground-service timeout handling, Room migration/threading, Tasker broadcasts on representative API levels, and real root-process-tree cancellation.

## Definition of done

The remediation is not complete merely because existing tests pass. Completion requires all of the following:

- Event activation either applies the requested effects or returns an explicit failure.
- Published active state corresponds to committed system state.
- Each semantic transition remains observable and is published once.
- Reload actually replaces validated configuration.
- Concurrent commands cannot overwrite or acknowledge one another incorrectly.
- Root transaction timeout leaves no live descendant and reports durable transaction state.
- Foreground commands survive process recreation or terminate durably as failed.
- Tasker support behavior matches the declared Android API range.
- Room access does not depend on caught main-thread exceptions.
- Debug and release unit tests compile and pass through Devtool.
- New regression tests fail against the pre-fix implementation and pass after the fix.

## Change-safety instructions for the implementing agent

- Inspect the worktree before editing and preserve unrelated user changes.
- Use Devtool for Android build/test/lint so the correct Termux SDK and AAPT2 provider are selected.
- Do not weaken exported-component authorization to make Tasker tests pass.
- Do not use `allowMainThreadQueries()` as the Room fix.
- Do not solve transaction timeout by merely increasing the timeout.
- Do not mark a profile active before its effects are committed.
- Keep machine-output schemas and compatibility digests stable unless deliberately versioned and updated across Go fixtures and Android parsing.
- Update both source and module payload mirrors when launcher/module files legitimately change.
