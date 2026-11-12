# XBSctl — Final consolidated engineering roadmap v2

**Reviewed:** 2026-11-12  
**XBSctl baseline:** `f048d2d5526a3d0d7632e477b6b4f052e41e30df` (`main`)  
**Devtool baseline:** `44eb17bbbd63194aa67cde0f7b63d5ada44fb96e` (`main`)  
**Cinderbox reusable emulator reference:** `2806231c51ad4060b5429abcc15dadaefe543a70` (`master`)  
**Qualification:** SOURCE REVIEW ONLY — no implementation or tests have been executed in this planning pass.  

This version supersedes the R00–R11 outline. It retains the original phases R00–R08 and **splits downstream testing into explicit R09 backend, R10 hosted emulator, R11 physical device, R12 adversarial/performance, and R13 release**. It tracks **17 code findings, nine infrastructure/testing findings, five QS tiles, 14 phases, 178 implementation tasks and 131 distinct test cases**.

## Completeness verdict

Conceptual audit coverage was already broad, but the previous document was not an executable acceptance plan. The new requirements explicitly close: (a) missing living ledger; (b) absent per-case Go/JVM/real-app testing matrix; (c) Cinderbox API36 reuse details and no-root truth boundary; (d) Gradle release/debug instrumentation ambiguity; (e) real process death versus Activity recreation; (f) exact CI test counts and raw `gh` output; (g) performance/battery regressions; (h) minSdk/API34/API36 compatibility; and (i) negative tests proving refusals.

## Non-negotiable policy

- Never change device state while running a source-only, GitHub-hosted CI, or ordinary emulator check. Physical root operations are opt-in and separately evidenced.
- Use `python3 overlay.zip` self-contained, source-pinned Python launchers for implementation overlays; preserve the original worktree, no blind force push/rebase. **This planning ZIP is documentation, not an executable code overlay.**
- Native Devtool orchestrates plan/preflight/build/test and ACTIONS journal/resume; Android Gradle runs on hosted GitHub Actions only, not in Termux.
- Actual `gh pr checks` stdout and failed job log excerpts are visible; never simulate success, accept zero tests, or substitute a PASS marker for execution.
- Source SHA, toolchain identity, test counts, artifact bytes, run ID+attempt and qualification tier must be recorded separately. No automatic publishing or installation.
- Do not weaken the historic Phase31 policy to make CI pass. Rebuild equivalent standalone evidence correctly.

## Dependencies and stage gates

| Phase | Priority | Depends on | Mandatory exit gate |
|---|---|---|---|
| **R00 — Baseline, migration and trustworthy test inventory** | P0 | Baseline | Standalone Gradle/Go source checks compile at exact SHA, complete test inventory exists, historical release guarantees retained, no fabricated green. |
| **R01 — Transactional and reversible tuning engine** | P0 | R00 | Failure injection proves original-state preservation, exact effect ownership, correct rollback, and recovery-required visibility. |
| **R02 — Daemon state, queue and crash recovery** | P0 | R01 | Every request has durable ID/state; crash at any boundary cannot silently double-apply or misreport success. |
| **R03 — Automatic event and profile lifecycle** | P1 | R02 | Boot/charging/screen/battery/manual transitions are observed, deterministic and idempotent on fake backend and qualified device. |
| **R04 — Magisk/KernelSU module install and artifact safety** | P1 | R02 | Fresh install/upgrade/rollback staging and reproducible module ZIPs verified without installing on user hardware. |
| **R05 — Android Companion, Tasker and config transactions** | P1 | R02, R03 | Android application acknowledges daemon outcomes, survives lifecycle interruptions, protects credentials and never confuses queued with committed. |
| **R06 — Native Devtool adoption and contracts** | P1 | R00, R02, R04, R05 | Devtool is source-pinned, target graph is accurate, legacy final seal accepts native Go, and all mandatory checks are represented. |
| **R07 — CI policy, optimization and actual GitHub diagnostics** | P1 | R06, R09 | All required checks execute and expose actual test counts and gh output; optimizations prove parity with full gate. |
| **R08 — Native Android Quick Settings controls** | P2 | R03, R05, R10 | Four initial tiles, one deferred recovery tile, all backed by daemon-confirmed state, safe on lockscreen and process death. |
| **R09 — Comprehensive Go/backend test harness** | P1 | R00, R01, R02 | Mandatory non-empty Go unit/integration, race, fuzz corpus, and fault-injection suites with precise coverage reporting. |
| **R10 — Cinderbox-derived hosted emulator and app journeys** | P1 | R00, R05, R09 | Real XBS activities/test APK run on fresh isolated API36 emulator with signed evidence, no live root or physical-device claims. |
| **R11 — Physical rooted-device qualification** | P2 | R04, R08, R10 | Read-only physical inspection first, then explicitly approved reversible tests, then isolated destructive effects on disposable hardware only. |
| **R12 — Adversarial, security and performance gates** | P2 | R09, R10, R11 | Negative tests demonstrate bounded failures, unsafe inputs refuse, performance budgets are measured, and diagnostics remain redacted. |
| **R13 — Release evidence, signoff and maintenance** | P3 | R07, R08, R09, R10, R11, R12 | All ledger items and required test cases accepted with exact source/bytes/signing/emulator/physical distinctions and safe install commands. |

## Test-lane policy

| Lane | Primary tool/context | What it can qualify | What it cannot qualify |
|---|---|---|---|
| Go fake backend | Hosted Go test, race, fuzz, deterministic Runner | Parsing, event state, effect ownership, recovery invariants | Real root kernel/sysfs effects |
| Android JVM | Hosted JDK21 AGP9.3.1 Gradle9.7.1 | Policies, Tasker/config/Tile state reducers, Room contracts, build | Actual SystemUI, emulator or physical behavior |
| API36 emulator | Cinderbox-derived Google APIs x86_64 Pixel6, real Activity + instrumentation | Real app UI, Navigation, Room migration, service callbacks, QS feasible UI, emulator state | ARM64 module execution, KernelSU/Magisk actual behavior, physical power savings |
| API26/API34 compatibility | Narrow hosted emulator shards | minSdk and broadcast/API-specific behavior | Full API36 release evidence |
| Physical rooted API36 | Explicitly authorized isolated device | Native ARM64 module, actual root daemon, user hardware QS, reversible effects | Authorization for disruptive GMS/Wi-Fi/CPU tests by default |
| Disposable high-impact lab | Separately authorized sacrificial hardware | Disruptive effect recovery | Unattended primary device usage |
| Signed release qualification | Protected hosted signing + independent receipt verification | Exact signed candidate provenance | Automatically proves physical runtime safety |

## Cinderbox-derived API36 emulator blueprint

Reference files (reuse patterns, not copy application-specific source or Java toolchain):

- `Mikeyphw/Cinderbox-Companion/.github/workflows/cinderbox-ux09d02-real-offline-evidence.yml`
- `scripts/devtool/ux09d02_emulator_ci.sh`, `scripts/devtool/r48_emulator_ci.sh`, `scripts/devtool/r48_capture.py`
- `.github/workflows/cinderbox-targeted.yml`, `scripts/ci/cinderbox_ci_policy.py`
- `app/src/androidTest/java/com/sdvsync/qualification/R48RealActivityDeviceTest.kt`

Workflow structure: **exact-SHA source/contracts** → **hosted JDK21/SDK36/AGP+Gradle build** → **verify KVM and bounded system-image download** → **ReactiveCircus/android-emulator-runner pinned to reviewed SHA** (API36, google_apis, x86_64, pixel_6, 2 cores, ~2GiB RAM) → **fresh app/test APK identity and install** → **real AndroidJUnit/Espresso UI journeys** → **privacy-safe test count and actual screen captures** → **source/run/attempt/hash evidence artifact**. No root effect claims.

**XBS-specific constraints:** `android-app/app/build.gradle` sets `testBuildType 'release'` and may synthesize `testDebugUnitTest`; verify the real Gradle task graph instead of assuming `assembleDebugAndroidTest` exists or runs the intended build. The project requires Java 21 and Gradle 9.7.1, not Cinderbox's Java 17 setup. XBS currently packages only Android ARM64 runtime binaries; the x86_64 emulator needs a controlled mock root/daemon boundary for UI verification. Test physical KernelSU/Magisk separately.

## Phase implementation requirements

### R00 — Baseline, migration and trustworthy test inventory

**Priority:** P0 · **Depends:** none · **Evidence gate:** G-R00 · **Status:** NOT_STARTED

- **R00-01** Pin and inventory exact repositories. **Done when:** Record source SHAs, branch, Go modules, Android modules, scripts and the originating audit findings.
- **R00-02** Repair standalone Devtool path in Android Gradle. **Done when:** Root Android build reads the repo-root .devtool.toml and rejects missing/incorrect path with an actionable error.
- **R00-03** Migrate Phase31 repository root detection. **Done when:** Historical apps/xbs root assumptions are replaced with verified standalone paths without weakening security checks.
- **R00-04** Replace obsolete 918-file source manifest. **Done when:** Manifest is regenerated from the intended source tree with exact size/path/hash validation and historical manifest preserved.
- **R00-05** Replace 237 legacy Python contract expectation. **Done when:** Relevant checks are ported or replaced by executable Go/Android tests; unported safety checks remain BLOCKED, not PASS.
- **R00-06** Restore valid Phase31 release gate topology. **Done when:** Release gate has correct test paths, independent signed-candidate checks and a negative test for missing evidence.
- **R00-07** Reconcile Gradle testBuildType release vs hosted debug lane. **Done when:** Actual release/debug androidTest task names and APK pairing are discovered and recorded; no assumed debug tests.
- **R00-08** Audit synthetic testDebugUnitTest alias. **Done when:** Report real executed task graph; remove/migrate misleading alias if it mislabels release-only tests.
- **R00-09** Validate Java21 AGP9.3.1 Gradle9.7.1 toolchain. **Done when:** Hosted compile and environment diagnostics report exact JDK/AGP/Gradle/SDK versions.
- **R00-10** Inventory existing Go JVM instrumentation and TestKit tests. **Done when:** List test IDs/counts and prove every selected test suite runs nonzero tests.
- **R00-11** Create source-bound baseline receipt and ledger. **Done when:** Baseline has correct commit SHA, no unreviewed mutation, and explicit not-tested/blocked fields.
- **R00-12** Preserve historical contracts and migrations. **Done when:** Legacy evidence is archived as historical rather than mistaken for new standalone qualification.

**Phase gate:** Standalone Gradle/Go source checks compile at exact SHA, complete test inventory exists, historical release guarantees retained, no fabricated green.

### R01 — Transactional and reversible tuning engine

**Priority:** P0 · **Depends:** R00 · **Evidence gate:** G-R01 · **Status:** NOT_STARTED

- **R01-01** Write-ahead durable per-effect journal. **Done when:** Original state and operation ID become durable before privileged mutation is attempted.
- **R01-02** Track effect lifecycle state machine. **Done when:** Planned/applying/applied/restoring/restored/failed/recovery-required are explicit and monotonic as appropriate.
- **R01-03** Propagate rollback errors instead of discarding them. **Done when:** Failed restore returns nonzero and retains unresolved records for later reconciliation.
- **R01-04** Rollback never claims applied unless verified. **Done when:** Result, CLI status and UI all distinguish full rollback from partial rollback.
- **R01-05** Snapshot exact CPU governor and core online states. **Done when:** Each file restores its own original value, with failed writes retaining a journal entry.
- **R01-06** Snapshot package suspension per user and ownership. **Done when:** Pre-existing suspended packages remain suspended; only XBS-owned suspensions are reversed.
- **R01-07** Enforce essentials and critical-app protection. **Done when:** Built-in essentials cannot be accidentally killed/suspended by denylist or template overrides.
- **R01-08** Probe and restore GMS initial state. **Done when:** Failed probe refuses modification; previously disabled GMS remains disabled after rollback.
- **R01-09** Probe and restore Wi-Fi initial state. **Done when:** Failed probe refuses disabling; off and on prior states are preserved exactly.
- **R01-10** Probe and restore low_ram property. **Done when:** Previous property and presence semantics are retained even if resetprop or lookup fails.
- **R01-11** Record Doze transition ownership and rollback outcome. **Done when:** Unforce and prior state handling are validated on supported devices and unsupported variants fail clearly.
- **R01-12** Bind renice recovery to stable process identity. **Done when:** PID reuse cannot cause a newly started unrelated process to receive old nice values.
- **R01-13** Treat non-reversible kill actions truthfully. **Done when:** Outcome and user-facing status do not promise restoration of killed processes.
- **R01-14** Define multi-profile effects ownership and precedence. **Done when:** Ending one active profile does not undo effects still needed by a different profile.
- **R01-15** Implement idempotent repeat application and restore. **Done when:** Duplicate equivalent requests do not drift or overwrite original-state values.
- **R01-16** Enforce dry-run non-mutation and truthful state. **Done when:** Dry run never changes files/PM/system properties and cannot masquerade as physically applied.
- **R01-17** Harden rule-file paths and size/permissions. **Done when:** Unsafe symlinks, untrusted paths, oversized rules or invalid content refuse before effect application.
- **R01-18** Test partial rollback on every effect family. **Done when:** Fault injection demonstrates durable unresolved snapshots and no false success.

**Phase gate:** Failure injection proves original-state preservation, exact effect ownership, correct rollback, and recovery-required visibility.

### R02 — Daemon state, queue and crash recovery

**Priority:** P0 · **Depends:** R01 · **Evidence gate:** G-R02 · **Status:** NOT_STARTED

- **R02-01** Fix controller per-category exit-code expression. **Done when:** Returned exit codes match the declared CLI contract for all known errors.
- **R02-02** Enforce strict inbox path/ownership/mode rules. **Done when:** Request, ack, journal and parent paths reject symlinks, bad modes and nonregular nodes.
- **R02-03** Validate request IDs and payload before paths. **Done when:** JSON IDs and commands have bounded grammars and cannot traverse ack directories.
- **R02-04** Journal command lifecycle and ack separately. **Done when:** Queued/claimed/executing/applied/failed/recovery required are durably distinguishable.
- **R02-05** Prove post-mutation crash deduplication. **Done when:** A reused .work command cannot re-apply already committed effects after lost ack.
- **R02-06** Add concurrent request serialization and conflict policy. **Done when:** Multiple control writers do not race on state files or privilege effects.
- **R02-07** Validate config on initial startup and reload. **Done when:** Same schema, ranges and semantic constraints apply at both entrypoints.
- **R02-08** Reconcile runtime state before ready state. **Done when:** No healthy/ready response is published while unapplied recovery work exists.
- **R02-09** Persist and expose failed reconciliation. **Done when:** Restart failure retains error and original journal; subsequent operation does not hide it.
- **R02-10** Configure bounded command deadlines and cancellation. **Done when:** Timeouts preserve unknown-outcome state until observed; no blind redispatch.
- **R02-11** Keep status and event changes atomic and versioned. **Done when:** Truncated/invalid status never causes optimistic transition or false acknowledgement.
- **R02-12** Define orderly termination and restoration. **Done when:** SIGTERM/SIGINT/HUP paths have explicit fail-closed restoration and service lifecycle reporting.
- **R02-13** Verify controller JSON and text schema compatibility. **Done when:** Consumers receive stable machine-readable states and exact error codes.

**Phase gate:** Every request has durable ID/state; crash at any boundary cannot silently double-apply or misreport success.

### R03 — Automatic event and profile lifecycle

**Priority:** P1 · **Depends:** R02 · **Evidence gate:** G-R03 · **Status:** NOT_STARTED

- **R03-01** Start configured boot event from late-start lifecycle. **Done when:** First boot applies only configured boot actions and records outcome.
- **R03-02** Implement charging/unplugging observation. **Done when:** Charging transition and disconnection deterministically reconcile desired profiles.
- **R03-03** Implement screen-off/on observation. **Done when:** Screen state changes apply and remove configured profile effects without app dependency.
- **R03-04** Implement low-power threshold and hysteresis. **Done when:** Boundary flapping is debounced and thresholds are explicitly validated.
- **R03-05** Implement keep_on_charge policy. **Done when:** Relevant screen/low-power actions respect charging policy and a documented priority order.
- **R03-06** Read header.delay and header.dry_run. **Done when:** Runtime policy honors validated config values and CLI overrides have documented precedence.
- **R03-07** Define quit-on-boot semantics. **Done when:** Quit never abandons unrecovered effects and leaves truthful state.
- **R03-08** Support custom/manual profiles consistently. **Done when:** CLI, Tasker, app and QS tiles share the same profile grammar and state model.
- **R03-09** Reconcile overlapping profiles. **Done when:** Remove only effects no longer requested by any active event.
- **R03-10** Persist desired and observed event state separately. **Done when:** Restart does not fabricate new charging/screen transitions or assume events applied.
- **R03-11** Debounce repeated broadcasts and detect missed state transitions. **Done when:** Repeated events do not duplicate actions; startup reads current observable state.
- **R03-12** Expose event source and effect outcomes in status. **Done when:** Status reports trigger, requested profile, final actual effects and last error.
- **R03-13** Add deterministic event fixture/test suite. **Done when:** Synthetic power/display/battery inputs exercise every transition without root.

**Phase gate:** Boot/charging/screen/battery/manual transitions are observed, deterministic and idempotent on fake backend and qualified device.

### R04 — Magisk/KernelSU module install and artifact safety

**Priority:** P1 · **Depends:** R02 · **Evidence gate:** G-R04 · **Status:** NOT_STARTED

- **R04-01** Repair missing xbs-paths.sh dependency or remove it cleanly. **Done when:** Fresh module installation initializes configuration through a verified bundled mechanism.
- **R04-02** Run explicit first-boot initialization and validation. **Done when:** Config required by control commands exists and validates before daemon readiness.
- **R04-03** Preserve user configuration during upgrade. **Done when:** Existing profile/list files remain intact and are migratable.
- **R04-04** Verify all three ARM64 Go executables and manifest. **Done when:** ELF machine, mode, hashes and declared package identity match.
- **R04-05** Implement safe replacement of staged module. **Done when:** Failed rename/validation cannot destroy the previously good stage.
- **R04-06** Check module ZIP path safety and reproducibility. **Done when:** No traversal/symlink/duplicate unsafe members; two independent outputs hash-equal.
- **R04-07** Repair module.prop vs update.json mismatch. **Done when:** Version/name/source/release links agree or updates are disabled safely.
- **R04-08** Verify KernelSU/Magisk installation layout under disposable fixtures. **Done when:** Correct script permissions, environment and entrypoints verified.
- **R04-09** Add upgrade/uninstall/rollback receipts. **Done when:** Evidence documents backup/restoration and no unrequested installation.
- **R04-10** Add validated installer command generation. **Done when:** The latest successful qualified module ZIP is resolved by exact run/artifact and verified hash.

**Phase gate:** Fresh install/upgrade/rollback staging and reproducible module ZIPs verified without installing on user hardware.

### R05 — Android Companion, Tasker and config transactions

**Priority:** P1 · **Depends:** R02, R03 · **Evidence gate:** G-R05 · **Status:** NOT_STARTED

- **R05-01** Wait for daemon-confirmed configuration reload. **Done when:** Transactions cannot claim applied until matching request ack and observed config state.
- **R05-02** Retain pending/recovery state on timeout. **Done when:** Unknown outcome is not rolled back blindly or labelled committed.
- **R05-03** Propagate eventbridge publication errors. **Done when:** Diagnostics show failed authentication/publication distinctly from unavailable data.
- **R05-04** Preserve HMAC verification and anti-replay. **Done when:** Forged, stale, reordered or wrong-key envelopes cannot enter authenticated state.
- **R05-05** Verify root runner cancellation and descendants. **Done when:** Timeout/cancel/process death leaves verified process termination or explicit recovery requirement.
- **R05-06** Verify foreground service lifecycle semantics. **Done when:** Android FGS denial/timeout/restart is reported and cannot lose owned commands.
- **R05-07** Keep Room and root reads off main thread. **Done when:** StrictMode and responsiveness tests detect accidental UI-thread disk/root work.
- **R05-08** Preserve signed Tasker action and condition contracts. **Done when:** Keystore, signer, sender UID and host-pin checks are exercised across APIs.
- **R05-09** Require exact command completion before Tasker success. **Done when:** Tasker never reports success for queued, failed, timed-out or unverified effects.
- **R05-10** Deduplicate user actions and background refresh. **Done when:** One tap produces at most one intended command, with bounded worker and refresh behavior.
- **R05-11** Improve status/feedback for all commands. **Done when:** UI shows pending, running, applied, cancelled, failed, offline and recovery-required.
- **R05-12** Test configuration transaction rollback, stale latest pointer and symlinks. **Done when:** Actual managed files remain correct under failures and contention.
- **R05-13** Test public intent and permission boundaries. **Done when:** Exported Tasker components reject unauthorized or malformed callers.
- **R05-14** Keep clear support reports with bounded redaction. **Done when:** Logs, tokens, filenames and device state are not leaked to hosted artifacts.

**Phase gate:** Android application acknowledges daemon outcomes, survives lifecycle interruptions, protects credentials and never confuses queued with committed.

### R06 — Native Devtool adoption and contracts

**Priority:** P1 · **Depends:** R00, R02, R04, R05 · **Evidence gate:** G-R06 · **Status:** NOT_STARTED

- **R06-01** Switch suitable Go actions to native Devtool runner. **Done when:** Tests/builds use supported native Go integrations with same semantics as previous jobs.
- **R06-02** Retain custom jobs only for project-specific checks. **Done when:** Contracts/final-seal commands remain explicit and nonduplicated.
- **R06-03** Fix Android target roots inputs outputs and artifact paths. **Done when:** Target-root-relative scopes identify real files with no doubled android-app prefix.
- **R06-04** Define exact Go-to-module DAG. **Done when:** Tests vet contracts seal and build prerequisites cannot be bypassed by alternate workflow.
- **R06-05** Define Go/Android cross-target validation graph. **Done when:** Independent nodes parallelize only after preflight verifies dependencies and hazards.
- **R06-06** Migrate VerifyRepositoryHandoff source checks. **Done when:** Final seal recognizes native Go runner and fails on missing mandatory jobs.
- **R06-07** Pin hosted Devtool source and installed wheel. **Done when:** Git checkout SHA, installed module identity and CLI schema are verified.
- **R06-08** Enable accurate affected target selection. **Done when:** Unknown/critical ownership forces conservative full validation.
- **R06-09** Adopt Devtool plan explain and graph preflight. **Done when:** Source scope, task ordering, read/write hazards are inspectable.
- **R06-10** Enable artifact verification and source-bound manifests. **Done when:** Missing APK/module artifact is a failure; verified bytes are not by themselves release qualified.
- **R06-11** Use Devtool Actions journal/resume safely. **Done when:** Already-dispatched attempts are inspected rather than blindly redispatched.
- **R06-12** Keep Termux free from Android Gradle invocation. **Done when:** Hosted-only Gradle rule is tested as a policy contract.

**Phase gate:** Devtool is source-pinned, target graph is accurate, legacy final seal accepts native Go, and all mandatory checks are represented.

### R07 — CI policy, optimization and actual GitHub diagnostics

**Priority:** P1 · **Depends:** R06, R09 · **Evidence gate:** G-R07 · **Status:** NOT_STARTED

- **R07-01** Create minimal canonical required-check workflows. **Done when:** Stable names and explicit prerequisites replace accidental workflow proliferation.
- **R07-02** Run Go test vet contracts seal and module build on exact SHA. **Done when:** Required jobs report observed test counts and artifact identity.
- **R07-03** Run real Android JVM lint compile instrumentation-APK jobs. **Done when:** Source-bound JDK21/AGP9.3.1/Gradle9.7.1 suite cannot be skipped by default.
- **R07-04** Pin Actions dependencies to immutable revisions. **Done when:** All hosted actions use reviewed full commit SHAs with least privilege.
- **R07-05** Make targeted PR gate conservative. **Done when:** Affected classification never skips shared-contract or security-sensitive dependencies.
- **R07-06** Implement scheduled/triggered full gate with exact-run reuse. **Done when:** No duplicated dispatch for the same SHA/attempt; no skipped full audit indefinitely.
- **R07-07** Use safe test/cache reuse. **Done when:** Cache is keyed/segregated so stale code/test outputs cannot qualify a changed source.
- **R07-08** Apply scope-safe parallel scheduling. **Done when:** Conflicting Go/Android/module work is serialized and unsafe concurrency is refused.
- **R07-09** Optimize Gradle task set without pruning validation. **Done when:** Redundant tasks removed only when dependencies guarantee identical coverage.
- **R07-10** Avoid unnecessary ADB startup during simple builds. **Done when:** Emulator acquisition only happens in actual emulator lanes.
- **R07-11** Print actual gh pr checks and failure log excerpts. **Done when:** Launcher displays raw/decoded check outputs with ANSI handling and meaningful errors.
- **R07-12** Adopt PR watcher with idempotent resume. **Done when:** It watches observed runs and reports attempts without creating duplicate runs.
- **R07-13** Produce per-lane source/run/attempt receipts. **Done when:** A workflow succeeded does not qualify an artifact if expected job or evidence is missing.
- **R07-14** Add CI policy failure-injection tests. **Done when:** Wrong SHA, missing tests, skipped checks, stale artifacts and duplicate runs all fail closed.
- **R07-15** Consolidate obsolete jobs and reduce noise. **Done when:** Job count is optimized only after required policy coverage is proved equivalent.

**Phase gate:** All required checks execute and expose actual test counts and gh output; optimizations prove parity with full gate.

### R08 — Native Android Quick Settings controls

**Priority:** P2 · **Depends:** R03, R05, R10 · **Evidence gate:** G-R08 · **Status:** NOT_STARTED

- **R08-01** Create shared XbsTileController and state adapter. **Done when:** All tiles use one authoritative daemon response and event bridge, not duplicated command logic.
- **R08-02** Add XBS Saver on/off tile. **Done when:** Manual profile activation toggles only after confirmed daemon success.
- **R08-03** Add Pause/Resume tile. **Done when:** Pausing/restoring respects outstanding effects and durable recovery journal.
- **R08-04** Add Profiles picker tile. **Done when:** Compact UI exposes built-in/custom profiles with real active status and correct back/navigation.
- **R08-05** Add Safe Mode tile. **Done when:** Safe mode requires verified restoration and deliberate exit policy.
- **R08-06** Add advanced Restore Defaults tile. **Done when:** Unlock and confirmation are mandatory, action never fires destructive restore directly from QS.
- **R08-07** Use TileService manifest and permission contracts. **Done when:** Exported TileService declares BIND_QUICK_SETTINGS_TILE and approved intents only.
- **R08-08** Map pending/error/offline state to truthful tile UI. **Done when:** Active/inactive/unavailable plus subtitle distinguish applying/recovery required.
- **R08-09** Synchronize Tasker/app/CLI/automatic profile changes. **Done when:** Tiles update through appropriate TileService listening callbacks.
- **R08-10** Bound background work and avoid SystemUI main-thread root calls. **Done when:** No continuous polling or blocking binder callbacks.
- **R08-11** Enforce rapid tap and idempotent command semantics. **Done when:** Repeated taps create at most one actual transition per requested state.
- **R08-12** Handle secure lockscreen and user unlock. **Done when:** Sensitive operations use platform unlock request or in-app confirmation.
- **R08-13** Expose safe Android Add Tile prompt with fallback. **Done when:** Supported devices can request tile addition; unsupported releases show instructions.
- **R08-14** Validate QS behavior on hosted emulator. **Done when:** Tests cover tile lifecycle, process death, no root, errors and external state; physical acceptance is deferred to R11.

**Phase gate:** Four initial tiles, one deferred recovery tile, all backed by daemon-confirmed state, safe on lockscreen and process death.

### R09 — Comprehensive Go/backend test harness

**Priority:** P1 · **Depends:** R00, R01, R02 · **Evidence gate:** G-R09 · **Status:** NOT_STARTED

- **R09-01** Build deterministic fake Android backend. **Done when:** File/PM/dumpsys/resetprop/process/charging/display responses are injectable without real root.
- **R09-02** Test all config schema branches and path rules. **Done when:** Invalid types, ranges, names, symlinks and atomic save errors refuse deterministically.
- **R09-03** Verify every effect-family apply/restore invariant. **Done when:** CPU, process, app, GMS, Wi-Fi, Doze and low_ram simulated state returns exactly.
- **R09-04** Run crash-point matrix over apply and restore. **Done when:** Every mutation boundary preserves a durable recovery path.
- **R09-05** Exercise queue concurrency and command deduplication. **Done when:** Race detector and restart simulation show no duplicate effect.
- **R09-06** Add fuzz targets for config, control and bridge parsers. **Done when:** Malformed and oversized input never causes panic or unbounded resource use.
- **R09-07** Add module ZIP and ELF validation negatives. **Done when:** Wrong architecture, symlink, zip slip, duplicate names and hash mismatch refuse.
- **R09-08** Run go test race and repeated seeds. **Done when:** Races/flake detection uses bounded test repetitions and records failures, not retries as fake PASS.
- **R09-09** Add CLI golden contract and exit-code tests. **Done when:** JSON/text schema snapshots verify backward compatibility, errors and success boundaries.
- **R09-10** Measure backend test coverage by package and risk. **Done when:** High-risk engine/queue/bridge paths have explicit branch/fault coverage and missing-cases ledger.
- **R09-11** Keep opt-in root integration suite isolated. **Done when:** Ordinary hosted test cannot toggle hardware or GMS; rooted tests require separate acknowledgement.

**Phase gate:** Mandatory non-empty Go unit/integration, race, fuzz corpus, and fault-injection suites with precise coverage reporting.

### R10 — Cinderbox-derived hosted emulator and app journeys

**Priority:** P1 · **Depends:** R00, R05, R09 · **Evidence gate:** G-R10 · **Status:** NOT_STARTED

- **R10-01** Port only reusable Cinderbox emulator orchestration. **Done when:** Hosted KVM/API36 Pixel6 setup adapted to XBS JDK21/Gradle9.7.1/AGP9.3.1.
- **R10-02** Require exact APK pair and package/signer/hash. **Done when:** Debug and instrumentation binaries belong to same source/run/attempt.
- **R10-03** Refuse physical device and stale/nonfresh app fixtures. **Done when:** Verify ro.kernel.qemu=1, API, unique ADB target and no existing XBS install.
- **R10-04** Use fake root/daemon service for app journeys. **Done when:** No Magisk/KernelSU or actual system tunables are required or claimed in emulator.
- **R10-05** Test real MainActivity and all navigation routes. **Done when:** Every screen is opened, scrolled, interacted with and returned from.
- **R10-06** Test config/events/essentials editor flows. **Done when:** Valid and invalid edits, rollback prompt, error feedback and state round trip.
- **R10-07** Test Tasker edit/action/condition through controlled senders. **Done when:** Valid signed fixture and malicious intents are distinguished.
- **R10-08** Test foreground service, notifications and process death. **Done when:** External host restarts app between test stages and verifies persisted outcome.
- **R10-09** Test Room v1-to-v2 migration and main-thread strictness. **Done when:** Data/index preservation and zero main-thread DB/root calls proven.
- **R10-10** Capture actual UI screenshot evidence. **Done when:** At multiple dp widths, theme, locale, rotation, status/nav bars; screenshots are non-secret and source bound.
- **R10-11** Capture redacted artifacts and non-empty test summaries. **Done when:** APK identity, tests started/passed, instrument exit, screenshot dimensions/hash, failure categories recorded.
- **R10-12** Run API26 and API34 compatibility shards selectively. **Done when:** Primary API36 lane is authoritative; minSdk and Tasker identity coverage have separate gates.
- **R10-13** Ensure no release or root qualification implied. **Done when:** Receipts explicitly say emulator_only, root_effects_not_tested, physical_qualified=false.

**Phase gate:** Real XBS activities/test APK run on fresh isolated API36 emulator with signed evidence, no live root or physical-device claims.

### R11 — Physical rooted-device qualification

**Priority:** P2 · **Depends:** R04, R08, R10 · **Evidence gate:** G-R11 · **Status:** NOT_STARTED

- **R11-01** Validate device/build identity without mutation. **Done when:** API36 ABI root manager package signature and module version captured read-only.
- **R11-02** Verify fresh module install/update with explicit approval. **Done when:** Installation is not initiated by source validation or ordinary emulator jobs.
- **R11-03** Exercise root daemon boot and actual charging/screen events. **Done when:** Trigger observations and restoration evidence include timestamps and state deltas.
- **R11-04** Verify CLI, Android and Tasker synchronization. **Done when:** One device action shows consistent command/state receipt in all components.
- **R11-05** Verify physical QS tile appearance, lockscreen and event updates. **Done when:** OEM-specific SystemUI behavior is documented separately.
- **R11-06** Test reversible process/Doze operations with bounded fixtures. **Done when:** Pre-state/post-state snapshots and cleanup pass with exact per-effect ownership.
- **R11-07** Use disposable hardware for CPU offline, GMS, Wi-Fi disruption. **Done when:** High-impact effects excluded from unattended ordinary device smoke.
- **R11-08** Verify real process-death and reboot recovery. **Done when:** Kill/cold-restart between stages and check journal before declaring restored.
- **R11-09** Prove cleanup and preserve user settings. **Done when:** Uninstall/rollback leaves no unauthorized modifications to system/profile state.
- **R11-10** Record separate device and module provenance. **Done when:** Physical qualification links exact build hash, source SHA, device API and consented test family.

**Phase gate:** Read-only physical inspection first, then explicitly approved reversible tests, then isolated destructive effects on disposable hardware only.

### R12 — Adversarial, security and performance gates

**Priority:** P2 · **Depends:** R09, R10, R11 · **Evidence gate:** G-R12 · **Status:** NOT_STARTED

- **R12-01** Fuzz file paths, JSON and archive metadata. **Done when:** No traversal/symlink escape, panic, resource blowup or partial output qualification.
- **R12-02** Fault-inject storage read/write fsync and rename. **Done when:** Atomic state survives interrupted persistence and reports unresolved recovery.
- **R12-03** Test wrong root identities and unauthorized Tasker senders. **Done when:** Invalid UID/cert/signature fails without command execution.
- **R12-04** Exercise HMAC key theft/replay and source permission negatives. **Done when:** Wrong key, stale sequence and unsafe filesystem fail closed.
- **R12-05** Test shell quoting and command injection defenses. **Done when:** User-controlled profile/file/argument values cannot escape expected argv/path.
- **R12-06** Inject missing APK/tests or zero-test instrumentation. **Done when:** Required test lane refuses instead of uploading fake PASS.
- **R12-07** Reject wrong source/run/attempt or artifact hash. **Done when:** Downloaded APK/module never qualified against a different commit.
- **R12-08** Test overlay interrupted transaction and dirty worktree. **Done when:** Source drift refuses and unrelated files/worktrees remain unchanged.
- **R12-09** Test lifecycle ANR/crash/memory regressions. **Done when:** Bounded startup/root service/QS response and no unbounded resource growth measured.
- **R12-10** Measure battery/wakeup overhead and polling. **Done when:** Backend event watcher and UI tiles do not create continuous high-rate CPU or wakeups.
- **R12-11** Verify screenshot and log privacy. **Done when:** Redacted report contains no credentials or raw private Android data.
- **R12-12** Require adversarial test matrix completeness. **Done when:** A missing mandatory negative case blocks final gate rather than marking green.

**Phase gate:** Negative tests demonstrate bounded failures, unsafe inputs refuse, performance budgets are measured, and diagnostics remain redacted.

### R13 — Release evidence, signoff and maintenance

**Priority:** P3 · **Depends:** R07, R08, R09, R10, R11, R12 · **Evidence gate:** G-R13 · **Status:** NOT_STARTED

- **R13-01** Reconcile every roadmap item and finding. **Done when:** Ledger records implemented revision, verified tests, unresolved blockers and no silently dropped promises.
- **R13-02** Validate signed-candidate identity and provenance. **Done when:** Package ID, versionCode, signer, source SHA, run ID/attempt and artifact hashes match.
- **R13-03** Keep debug vs release vs rooted-device claims separate. **Done when:** CI build success cannot be mistaken for signed release or device qualification.
- **R13-04** Complete regenerated Phase31-derived release evidence chain. **Done when:** No historical 918/237 contracts are accepted as current evidence.
- **R13-05** Verify baseline profile, benchmark and migration qualification. **Done when:** Build outputs and profiling evidence correspond to exact candidate.
- **R13-06** Publish a machine-readable candidate manifest and SHA256SUMS. **Done when:** Explicit artifact links and metadata are independently verifiable.
- **R13-07** Document install/upgrade/recovery/uninstall procedures. **Done when:** Commands resolve latest successful *qualified* artifact without touching phone automatically.
- **R13-08** Deliver comprehensive troubleshooting and logs policy. **Done when:** Users can inspect actual gh checks, source-bound receipts and privacy-safe failure causes.
- **R13-09** Run mandatory full gate after optimizations. **Done when:** No changes to affected-selection prevent complete regression qualification.
- **R13-10** Archive old ledger decisions and superseded findings. **Done when:** History remains audit-friendly and corrected audit claims stay retired.
- **R13-11** Block release on unresolved P0/P1 findings. **Done when:** Every critical issue has proof, not merely a fix commit.

**Phase gate:** All ledger items and required test cases accepted with exact source/bytes/signing/emulator/physical distinctions and safe install commands.

## Universal definition of done

Each task requires implemented exact Git SHA, reviewed changed-file allowlist, actual before/after behavior and relevant automated test evidence. Each phase requires its G-Rxx gate and nonempty matching test cases from `XBS_TEST_MATRIX.json`. Source review is never a PASS. A failure or skip remains visible and blocks dependent qualification. Device-specific and signing evidence must be separately bound to the same intended candidate. Unqualified fixtures are not approved releases.

## Changed audit assertions to retire

1. The old claim that the Go daemon has no tuning engine is outdated: it calls `tuning.Engine.Apply/Restore/Maintain`. Correctness and recovery still need testing.
2. `.devtool-wrapper.lock` is an advisory wrapper replacement lock, **not** a version lock. Use an independent immutable hosted Devtool pin.

## Maintained contract count

The implementation ledger tracks all 17 CODE findings from the source audit, plus independent infra/testing findings, the DT01/DT02/CI03/OPT04/EV05/HF06 requirements, all five Quick Settings tiles, and complete backend/app/device testing. Updates are append-only in the ledger change history; changing acceptance criteria requires explicit justification and source-bound revalidation.
