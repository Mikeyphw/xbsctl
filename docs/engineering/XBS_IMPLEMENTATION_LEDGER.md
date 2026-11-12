# XBSctl — Living implementation ledger v2

**Current planning state:** First implementation overlay PREPARED; selected tasks are IN_PROGRESS. **No GitHub implementation has been committed, and no test evidence is claimed.**  
**Editable source of truth:** `XBS_IMPLEMENTATION_LEDGER.json` (schema `xbs-consolidated-ledger/v2`).  
**Update protocol:** After each overlay/PR, record exact branch, source SHA, changed paths, Devtool plan/preflight/overlay result, PR number, real `gh pr checks` output, run ID+attempt, test-count receipt, artifact SHA256, qualification tier and blockers. Set `ACCEPTED` only after required gates; never advance on an unexecuted test.  

## Finding ledger (all previously reported + new explicit infrastructure issues)

| Finding | Severity | Status | Phase | Evidence path | Description |
|---|---|---|---|---|---|
| CODE-01 | P0 | OPEN | R01 | `internal/tuning/tuning.go` | Rollback errors discarded and originals lost on failure |
| CODE-02 | P0 | OPEN | R01 | `internal/tuning/tuning.go` | Package suspension does not preserve per-package prior state |
| CODE-03 | P1 | OPEN | R05 | `android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsConfigTransaction.java` | Configuration reload is queued, not daemon-confirmed |
| CODE-04 | P0 | OPEN | R01 | `internal/daemon/daemon.go` | Durable state persists after privileged effect commit window |
| CODE-05 | P1 | OPEN | R04 | `module/customize.sh` | Missing module initialization helper xbs-paths.sh |
| CODE-06 | P1 | OPEN | R05 | `internal/daemon/daemon.go` | Authenticated bridge publication error ignored |
| CODE-07 | P1 | OPEN | R03 | `internal/daemon/daemon.go` | Automatic boot/charging/screen/low-power profiles not wired |
| CODE-08 | P1 | OPEN | R02 | `internal/control/control.go` | Controller error-code expression combines with 6 |
| CODE-09 | P0 | OPEN | R02 | `internal/control/inbox.go` | Queue post-effect pre-ack crash can replay request |
| CODE-10 | P0 | OPEN | R01 | `internal/tuning/tuning.go` | Essentials allowlist not applied to tuning package filter |
| CODE-11 | P1 | OPEN | R02 | `internal/config/config.go` | Initial daemon config load does not validate semantic schema |
| CODE-12 | P2 | OPEN | R04 | `module/update.json` | Module update JSON version and upstream source mismatch |
| CODE-13 | P0 | OPEN | R01 | `internal/tuning/tuning.go` | Original GMS/WiFi/lowRAM probe errors ignored |
| CODE-14 | P1 | OPEN | R02 | `internal/control/inbox.go` | Inbox request ID/path and file trust boundary not strict |
| CODE-15 | P1 | OPEN | R06 | `internal/module/module.go` | FinalSeal handoff asserts old command-runner structure |
| CODE-16 | P2 | OPEN | R04 | `internal/module/module.go` | StageModule deletes previous destination before replacement |
| CODE-17 | P1 | OPEN | R01 | `internal/tuning/tuning.go` | Dry-run may advertise state as applied without physical mutation |
| INFRA-01 | P0 | OPEN | R00 | `android-app/build.gradle` | Incorrect standalone Gradle .devtool.toml path |
| INFRA-02 | P0 | OPEN | R00 | `android-app/tools/phase31_release_gate.py` | Stale Phase31 918-file/237-test monorepo contract |
| INFRA-03 | P1 | OPEN | R07 | `.github/workflows/devtool-xbs-ci.yml` | Android job opt-in and not executed in sole historical XBS workflow |
| INFRA-04 | P1 | OPEN | R00 | `android-app/app/build.gradle` | Android build testBuildType release and synthetic debug unit-test alias |
| INFRA-05 | P1 | OPEN | R06 | `.devtool.toml` | Devtool target inputs/outputs double-prefix and artifact verify disabled |
| INFRA-06 | P1 | OPEN | R06 | `.devtool.toml` | Devtool ci-module omits vet, contracts, final seal |
| INFRA-07 | P1 | OPEN | R07 | `.github/workflows/devtool-xbs-ci.yml` | Mutable workflow uses and no hosted pinned Devtool runtime |
| TEST-01 | P1 | OPEN | R10 | `.github/workflows/devtool-xbs-ci.yml` | No mandatory actual hosted XBS instrumentation/emulator lane |
| TEST-02 | P1 | OPEN | R11 | `internal/tuning/root_integration_test.go` | Opt-in root integration suite not represented by hosted green |

## Implementation task ledger

Each row's acceptance is in the JSON and final roadmap. Every task begins **NOT_STARTED**. No date, PR or SHA is invented.

| Task | Phase | Priority | Status | Acceptance summary |
|---|---|---|---|---|
| R00-01 | R00 | P0 | IN_PROGRESS | Record source SHAs, branch, Go modules, Android modules, scripts and the originating audit findings. |
| R00-02 | R00 | P0 | IN_PROGRESS | Root Android build reads the repo-root .devtool.toml and rejects missing/incorrect path with an actionable error. |
| R00-03 | R00 | P0 | NOT_STARTED | Historical apps/xbs root assumptions are replaced with verified standalone paths without weakening security checks. |
| R00-04 | R00 | P0 | NOT_STARTED | Manifest is regenerated from the intended source tree with exact size/path/hash validation and historical manifest preserved. |
| R00-05 | R00 | P0 | NOT_STARTED | Relevant checks are ported or replaced by executable Go/Android tests; unported safety checks remain BLOCKED, not PASS. |
| R00-06 | R00 | P0 | NOT_STARTED | Release gate has correct test paths, independent signed-candidate checks and a negative test for missing evidence. |
| R00-07 | R00 | P0 | NOT_STARTED | Actual release/debug androidTest task names and APK pairing are discovered and recorded; no assumed debug tests. |
| R00-08 | R00 | P0 | NOT_STARTED | Report real executed task graph; remove/migrate misleading alias if it mislabels release-only tests. |
| R00-09 | R00 | P0 | NOT_STARTED | Hosted compile and environment diagnostics report exact JDK/AGP/Gradle/SDK versions. |
| R00-10 | R00 | P0 | NOT_STARTED | List test IDs/counts and prove every selected test suite runs nonzero tests. |
| R00-11 | R00 | P0 | IN_PROGRESS | Baseline has correct commit SHA, no unreviewed mutation, and explicit not-tested/blocked fields. |
| R00-12 | R00 | P0 | NOT_STARTED | Legacy evidence is archived as historical rather than mistaken for new standalone qualification. |
| R01-01 | R01 | P0 | NOT_STARTED | Original state and operation ID become durable before privileged mutation is attempted. |
| R01-02 | R01 | P0 | NOT_STARTED | Planned/applying/applied/restoring/restored/failed/recovery-required are explicit and monotonic as appropriate. |
| R01-03 | R01 | P0 | NOT_STARTED | Failed restore returns nonzero and retains unresolved records for later reconciliation. |
| R01-04 | R01 | P0 | NOT_STARTED | Result, CLI status and UI all distinguish full rollback from partial rollback. |
| R01-05 | R01 | P0 | NOT_STARTED | Each file restores its own original value, with failed writes retaining a journal entry. |
| R01-06 | R01 | P0 | NOT_STARTED | Pre-existing suspended packages remain suspended; only XBS-owned suspensions are reversed. |
| R01-07 | R01 | P0 | NOT_STARTED | Built-in essentials cannot be accidentally killed/suspended by denylist or template overrides. |
| R01-08 | R01 | P0 | IN_PROGRESS | Failed probe refuses modification; previously disabled GMS remains disabled after rollback. |
| R01-09 | R01 | P0 | IN_PROGRESS | Failed probe refuses disabling; off and on prior states are preserved exactly. |
| R01-10 | R01 | P0 | IN_PROGRESS | Previous property and presence semantics are retained even if resetprop or lookup fails. |
| R01-11 | R01 | P0 | NOT_STARTED | Unforce and prior state handling are validated on supported devices and unsupported variants fail clearly. |
| R01-12 | R01 | P0 | NOT_STARTED | PID reuse cannot cause a newly started unrelated process to receive old nice values. |
| R01-13 | R01 | P0 | NOT_STARTED | Outcome and user-facing status do not promise restoration of killed processes. |
| R01-14 | R01 | P0 | NOT_STARTED | Ending one active profile does not undo effects still needed by a different profile. |
| R01-15 | R01 | P0 | NOT_STARTED | Duplicate equivalent requests do not drift or overwrite original-state values. |
| R01-16 | R01 | P0 | NOT_STARTED | Dry run never changes files/PM/system properties and cannot masquerade as physically applied. |
| R01-17 | R01 | P0 | NOT_STARTED | Unsafe symlinks, untrusted paths, oversized rules or invalid content refuse before effect application. |
| R01-18 | R01 | P0 | NOT_STARTED | Fault injection demonstrates durable unresolved snapshots and no false success. |
| R02-01 | R02 | P0 | IN_PROGRESS | Returned exit codes match the declared CLI contract for all known errors. |
| R02-02 | R02 | P0 | NOT_STARTED | Request, ack, journal and parent paths reject symlinks, bad modes and nonregular nodes. |
| R02-03 | R02 | P0 | NOT_STARTED | JSON IDs and commands have bounded grammars and cannot traverse ack directories. |
| R02-04 | R02 | P0 | NOT_STARTED | Queued/claimed/executing/applied/failed/recovery required are durably distinguishable. |
| R02-05 | R02 | P0 | NOT_STARTED | A reused .work command cannot re-apply already committed effects after lost ack. |
| R02-06 | R02 | P0 | NOT_STARTED | Multiple control writers do not race on state files or privilege effects. |
| R02-07 | R02 | P0 | IN_PROGRESS | Same schema, ranges and semantic constraints apply at both entrypoints. |
| R02-08 | R02 | P0 | NOT_STARTED | No healthy/ready response is published while unapplied recovery work exists. |
| R02-09 | R02 | P0 | NOT_STARTED | Restart failure retains error and original journal; subsequent operation does not hide it. |
| R02-10 | R02 | P0 | NOT_STARTED | Timeouts preserve unknown-outcome state until observed; no blind redispatch. |
| R02-11 | R02 | P0 | NOT_STARTED | Truncated/invalid status never causes optimistic transition or false acknowledgement. |
| R02-12 | R02 | P0 | NOT_STARTED | SIGTERM/SIGINT/HUP paths have explicit fail-closed restoration and service lifecycle reporting. |
| R02-13 | R02 | P0 | IN_PROGRESS | Consumers receive stable machine-readable states and exact error codes. |
| R03-01 | R03 | P1 | NOT_STARTED | First boot applies only configured boot actions and records outcome. |
| R03-02 | R03 | P1 | NOT_STARTED | Charging transition and disconnection deterministically reconcile desired profiles. |
| R03-03 | R03 | P1 | NOT_STARTED | Screen state changes apply and remove configured profile effects without app dependency. |
| R03-04 | R03 | P1 | NOT_STARTED | Boundary flapping is debounced and thresholds are explicitly validated. |
| R03-05 | R03 | P1 | NOT_STARTED | Relevant screen/low-power actions respect charging policy and a documented priority order. |
| R03-06 | R03 | P1 | NOT_STARTED | Runtime policy honors validated config values and CLI overrides have documented precedence. |
| R03-07 | R03 | P1 | NOT_STARTED | Quit never abandons unrecovered effects and leaves truthful state. |
| R03-08 | R03 | P1 | NOT_STARTED | CLI, Tasker, app and QS tiles share the same profile grammar and state model. |
| R03-09 | R03 | P1 | NOT_STARTED | Remove only effects no longer requested by any active event. |
| R03-10 | R03 | P1 | NOT_STARTED | Restart does not fabricate new charging/screen transitions or assume events applied. |
| R03-11 | R03 | P1 | NOT_STARTED | Repeated events do not duplicate actions; startup reads current observable state. |
| R03-12 | R03 | P1 | NOT_STARTED | Status reports trigger, requested profile, final actual effects and last error. |
| R03-13 | R03 | P1 | NOT_STARTED | Synthetic power/display/battery inputs exercise every transition without root. |
| R04-01 | R04 | P1 | IN_PROGRESS | Fresh module installation initializes configuration through a verified bundled mechanism. |
| R04-02 | R04 | P1 | IN_PROGRESS | Config required by control commands exists and validates before daemon readiness. |
| R04-03 | R04 | P1 | NOT_STARTED | Existing profile/list files remain intact and are migratable. |
| R04-04 | R04 | P1 | NOT_STARTED | ELF machine, mode, hashes and declared package identity match. |
| R04-05 | R04 | P1 | NOT_STARTED | Failed rename/validation cannot destroy the previously good stage. |
| R04-06 | R04 | P1 | NOT_STARTED | No traversal/symlink/duplicate unsafe members; two independent outputs hash-equal. |
| R04-07 | R04 | P1 | NOT_STARTED | Version/name/source/release links agree or updates are disabled safely. |
| R04-08 | R04 | P1 | NOT_STARTED | Correct script permissions, environment and entrypoints verified. |
| R04-09 | R04 | P1 | NOT_STARTED | Evidence documents backup/restoration and no unrequested installation. |
| R04-10 | R04 | P1 | NOT_STARTED | The latest successful qualified module ZIP is resolved by exact run/artifact and verified hash. |
| R05-01 | R05 | P1 | NOT_STARTED | Transactions cannot claim applied until matching request ack and observed config state. |
| R05-02 | R05 | P1 | NOT_STARTED | Unknown outcome is not rolled back blindly or labelled committed. |
| R05-03 | R05 | P1 | NOT_STARTED | Diagnostics show failed authentication/publication distinctly from unavailable data. |
| R05-04 | R05 | P1 | NOT_STARTED | Forged, stale, reordered or wrong-key envelopes cannot enter authenticated state. |
| R05-05 | R05 | P1 | NOT_STARTED | Timeout/cancel/process death leaves verified process termination or explicit recovery requirement. |
| R05-06 | R05 | P1 | NOT_STARTED | Android FGS denial/timeout/restart is reported and cannot lose owned commands. |
| R05-07 | R05 | P1 | NOT_STARTED | StrictMode and responsiveness tests detect accidental UI-thread disk/root work. |
| R05-08 | R05 | P1 | NOT_STARTED | Keystore, signer, sender UID and host-pin checks are exercised across APIs. |
| R05-09 | R05 | P1 | NOT_STARTED | Tasker never reports success for queued, failed, timed-out or unverified effects. |
| R05-10 | R05 | P1 | NOT_STARTED | One tap produces at most one intended command, with bounded worker and refresh behavior. |
| R05-11 | R05 | P1 | NOT_STARTED | UI shows pending, running, applied, cancelled, failed, offline and recovery-required. |
| R05-12 | R05 | P1 | NOT_STARTED | Actual managed files remain correct under failures and contention. |
| R05-13 | R05 | P1 | NOT_STARTED | Exported Tasker components reject unauthorized or malformed callers. |
| R05-14 | R05 | P1 | NOT_STARTED | Logs, tokens, filenames and device state are not leaked to hosted artifacts. |
| R06-01 | R06 | P1 | NOT_STARTED | Tests/builds use supported native Go integrations with same semantics as previous jobs. |
| R06-02 | R06 | P1 | NOT_STARTED | Contracts/final-seal commands remain explicit and nonduplicated. |
| R06-03 | R06 | P1 | NOT_STARTED | Target-root-relative scopes identify real files with no doubled android-app prefix. |
| R06-04 | R06 | P1 | NOT_STARTED | Tests vet contracts seal and build prerequisites cannot be bypassed by alternate workflow. |
| R06-05 | R06 | P1 | NOT_STARTED | Independent nodes parallelize only after preflight verifies dependencies and hazards. |
| R06-06 | R06 | P1 | NOT_STARTED | Final seal recognizes native Go runner and fails on missing mandatory jobs. |
| R06-07 | R06 | P1 | NOT_STARTED | Git checkout SHA, installed module identity and CLI schema are verified. |
| R06-08 | R06 | P1 | NOT_STARTED | Unknown/critical ownership forces conservative full validation. |
| R06-09 | R06 | P1 | NOT_STARTED | Source scope, task ordering, read/write hazards are inspectable. |
| R06-10 | R06 | P1 | NOT_STARTED | Missing APK/module artifact is a failure; verified bytes are not by themselves release qualified. |
| R06-11 | R06 | P1 | NOT_STARTED | Already-dispatched attempts are inspected rather than blindly redispatched. |
| R06-12 | R06 | P1 | NOT_STARTED | Hosted-only Gradle rule is tested as a policy contract. |
| R07-01 | R07 | P1 | NOT_STARTED | Stable names and explicit prerequisites replace accidental workflow proliferation. |
| R07-02 | R07 | P1 | NOT_STARTED | Required jobs report observed test counts and artifact identity. |
| R07-03 | R07 | P1 | NOT_STARTED | Source-bound JDK21/AGP9.3.1/Gradle9.7.1 suite cannot be skipped by default. |
| R07-04 | R07 | P1 | NOT_STARTED | All hosted actions use reviewed full commit SHAs with least privilege. |
| R07-05 | R07 | P1 | NOT_STARTED | Affected classification never skips shared-contract or security-sensitive dependencies. |
| R07-06 | R07 | P1 | NOT_STARTED | No duplicated dispatch for the same SHA/attempt; no skipped full audit indefinitely. |
| R07-07 | R07 | P1 | NOT_STARTED | Cache is keyed/segregated so stale code/test outputs cannot qualify a changed source. |
| R07-08 | R07 | P1 | NOT_STARTED | Conflicting Go/Android/module work is serialized and unsafe concurrency is refused. |
| R07-09 | R07 | P1 | NOT_STARTED | Redundant tasks removed only when dependencies guarantee identical coverage. |
| R07-10 | R07 | P1 | NOT_STARTED | Emulator acquisition only happens in actual emulator lanes. |
| R07-11 | R07 | P1 | NOT_STARTED | Launcher displays raw/decoded check outputs with ANSI handling and meaningful errors. |
| R07-12 | R07 | P1 | NOT_STARTED | It watches observed runs and reports attempts without creating duplicate runs. |
| R07-13 | R07 | P1 | NOT_STARTED | A workflow succeeded does not qualify an artifact if expected job or evidence is missing. |
| R07-14 | R07 | P1 | NOT_STARTED | Wrong SHA, missing tests, skipped checks, stale artifacts and duplicate runs all fail closed. |
| R07-15 | R07 | P1 | NOT_STARTED | Job count is optimized only after required policy coverage is proved equivalent. |
| R08-01 | R08 | P2 | NOT_STARTED | All tiles use one authoritative daemon response and event bridge, not duplicated command logic. |
| R08-02 | R08 | P2 | NOT_STARTED | Manual profile activation toggles only after confirmed daemon success. |
| R08-03 | R08 | P2 | NOT_STARTED | Pausing/restoring respects outstanding effects and durable recovery journal. |
| R08-04 | R08 | P2 | NOT_STARTED | Compact UI exposes built-in/custom profiles with real active status and correct back/navigation. |
| R08-05 | R08 | P2 | NOT_STARTED | Safe mode requires verified restoration and deliberate exit policy. |
| R08-06 | R08 | P2 | NOT_STARTED | Unlock and confirmation are mandatory, action never fires destructive restore directly from QS. |
| R08-07 | R08 | P2 | NOT_STARTED | Exported TileService declares BIND_QUICK_SETTINGS_TILE and approved intents only. |
| R08-08 | R08 | P2 | NOT_STARTED | Active/inactive/unavailable plus subtitle distinguish applying/recovery required. |
| R08-09 | R08 | P2 | NOT_STARTED | Tiles update through appropriate TileService listening callbacks. |
| R08-10 | R08 | P2 | NOT_STARTED | No continuous polling or blocking binder callbacks. |
| R08-11 | R08 | P2 | NOT_STARTED | Repeated taps create at most one actual transition per requested state. |
| R08-12 | R08 | P2 | NOT_STARTED | Sensitive operations use platform unlock request or in-app confirmation. |
| R08-13 | R08 | P2 | NOT_STARTED | Supported devices can request tile addition; unsupported releases show instructions. |
| R08-14 | R08 | P2 | NOT_STARTED | Tests cover tile lifecycle, process death, no root, errors and external state; physical acceptance is deferred to R11. |
| R09-01 | R09 | P1 | NOT_STARTED | File/PM/dumpsys/resetprop/process/charging/display responses are injectable without real root. |
| R09-02 | R09 | P1 | NOT_STARTED | Invalid types, ranges, names, symlinks and atomic save errors refuse deterministically. |
| R09-03 | R09 | P1 | NOT_STARTED | CPU, process, app, GMS, Wi-Fi, Doze and low_ram simulated state returns exactly. |
| R09-04 | R09 | P1 | NOT_STARTED | Every mutation boundary preserves a durable recovery path. |
| R09-05 | R09 | P1 | NOT_STARTED | Race detector and restart simulation show no duplicate effect. |
| R09-06 | R09 | P1 | NOT_STARTED | Malformed and oversized input never causes panic or unbounded resource use. |
| R09-07 | R09 | P1 | NOT_STARTED | Wrong architecture, symlink, zip slip, duplicate names and hash mismatch refuse. |
| R09-08 | R09 | P1 | NOT_STARTED | Races/flake detection uses bounded test repetitions and records failures, not retries as fake PASS. |
| R09-09 | R09 | P1 | NOT_STARTED | JSON/text schema snapshots verify backward compatibility, errors and success boundaries. |
| R09-10 | R09 | P1 | NOT_STARTED | High-risk engine/queue/bridge paths have explicit branch/fault coverage and missing-cases ledger. |
| R09-11 | R09 | P1 | NOT_STARTED | Ordinary hosted test cannot toggle hardware or GMS; rooted tests require separate acknowledgement. |
| R10-01 | R10 | P1 | NOT_STARTED | Hosted KVM/API36 Pixel6 setup adapted to XBS JDK21/Gradle9.7.1/AGP9.3.1. |
| R10-02 | R10 | P1 | NOT_STARTED | Debug and instrumentation binaries belong to same source/run/attempt. |
| R10-03 | R10 | P1 | NOT_STARTED | Verify ro.kernel.qemu=1, API, unique ADB target and no existing XBS install. |
| R10-04 | R10 | P1 | NOT_STARTED | No Magisk/KernelSU or actual system tunables are required or claimed in emulator. |
| R10-05 | R10 | P1 | NOT_STARTED | Every screen is opened, scrolled, interacted with and returned from. |
| R10-06 | R10 | P1 | NOT_STARTED | Valid and invalid edits, rollback prompt, error feedback and state round trip. |
| R10-07 | R10 | P1 | NOT_STARTED | Valid signed fixture and malicious intents are distinguished. |
| R10-08 | R10 | P1 | NOT_STARTED | External host restarts app between test stages and verifies persisted outcome. |
| R10-09 | R10 | P1 | NOT_STARTED | Data/index preservation and zero main-thread DB/root calls proven. |
| R10-10 | R10 | P1 | NOT_STARTED | At multiple dp widths, theme, locale, rotation, status/nav bars; screenshots are non-secret and source bound. |
| R10-11 | R10 | P1 | NOT_STARTED | APK identity, tests started/passed, instrument exit, screenshot dimensions/hash, failure categories recorded. |
| R10-12 | R10 | P1 | NOT_STARTED | Primary API36 lane is authoritative; minSdk and Tasker identity coverage have separate gates. |
| R10-13 | R10 | P1 | NOT_STARTED | Receipts explicitly say emulator_only, root_effects_not_tested, physical_qualified=false. |
| R11-01 | R11 | P2 | NOT_STARTED | API36 ABI root manager package signature and module version captured read-only. |
| R11-02 | R11 | P2 | NOT_STARTED | Installation is not initiated by source validation or ordinary emulator jobs. |
| R11-03 | R11 | P2 | NOT_STARTED | Trigger observations and restoration evidence include timestamps and state deltas. |
| R11-04 | R11 | P2 | NOT_STARTED | One device action shows consistent command/state receipt in all components. |
| R11-05 | R11 | P2 | NOT_STARTED | OEM-specific SystemUI behavior is documented separately. |
| R11-06 | R11 | P2 | NOT_STARTED | Pre-state/post-state snapshots and cleanup pass with exact per-effect ownership. |
| R11-07 | R11 | P2 | NOT_STARTED | High-impact effects excluded from unattended ordinary device smoke. |
| R11-08 | R11 | P2 | NOT_STARTED | Kill/cold-restart between stages and check journal before declaring restored. |
| R11-09 | R11 | P2 | NOT_STARTED | Uninstall/rollback leaves no unauthorized modifications to system/profile state. |
| R11-10 | R11 | P2 | NOT_STARTED | Physical qualification links exact build hash, source SHA, device API and consented test family. |
| R12-01 | R12 | P2 | NOT_STARTED | No traversal/symlink escape, panic, resource blowup or partial output qualification. |
| R12-02 | R12 | P2 | NOT_STARTED | Atomic state survives interrupted persistence and reports unresolved recovery. |
| R12-03 | R12 | P2 | NOT_STARTED | Invalid UID/cert/signature fails without command execution. |
| R12-04 | R12 | P2 | NOT_STARTED | Wrong key, stale sequence and unsafe filesystem fail closed. |
| R12-05 | R12 | P2 | NOT_STARTED | User-controlled profile/file/argument values cannot escape expected argv/path. |
| R12-06 | R12 | P2 | NOT_STARTED | Required test lane refuses instead of uploading fake PASS. |
| R12-07 | R12 | P2 | NOT_STARTED | Downloaded APK/module never qualified against a different commit. |
| R12-08 | R12 | P2 | NOT_STARTED | Source drift refuses and unrelated files/worktrees remain unchanged. |
| R12-09 | R12 | P2 | NOT_STARTED | Bounded startup/root service/QS response and no unbounded resource growth measured. |
| R12-10 | R12 | P2 | NOT_STARTED | Backend event watcher and UI tiles do not create continuous high-rate CPU or wakeups. |
| R12-11 | R12 | P2 | NOT_STARTED | Redacted report contains no credentials or raw private Android data. |
| R12-12 | R12 | P2 | NOT_STARTED | A missing mandatory negative case blocks final gate rather than marking green. |
| R13-01 | R13 | P3 | NOT_STARTED | Ledger records implemented revision, verified tests, unresolved blockers and no silently dropped promises. |
| R13-02 | R13 | P3 | NOT_STARTED | Package ID, versionCode, signer, source SHA, run ID/attempt and artifact hashes match. |
| R13-03 | R13 | P3 | NOT_STARTED | CI build success cannot be mistaken for signed release or device qualification. |
| R13-04 | R13 | P3 | NOT_STARTED | No historical 918/237 contracts are accepted as current evidence. |
| R13-05 | R13 | P3 | NOT_STARTED | Build outputs and profiling evidence correspond to exact candidate. |
| R13-06 | R13 | P3 | NOT_STARTED | Explicit artifact links and metadata are independently verifiable. |
| R13-07 | R13 | P3 | NOT_STARTED | Commands resolve latest successful *qualified* artifact without touching phone automatically. |
| R13-08 | R13 | P3 | NOT_STARTED | Users can inspect actual gh checks, source-bound receipts and privacy-safe failure causes. |
| R13-09 | R13 | P3 | NOT_STARTED | No changes to affected-selection prevent complete regression qualification. |
| R13-10 | R13 | P3 | NOT_STARTED | History remains audit-friendly and corrected audit claims stay retired. |
| R13-11 | R13 | P3 | NOT_STARTED | Every critical issue has proof, not merely a fix commit. |

## Phase-level evidence and change log

| Gate | Initial status | Required proof |
|---|---|---|
| G-R00 | NOT_STARTED | Standalone Gradle/Go source checks compile at exact SHA, complete test inventory exists, historical release guarantees retained, no fabricated green. |
| G-R01 | NOT_STARTED | Failure injection proves original-state preservation, exact effect ownership, correct rollback, and recovery-required visibility. |
| G-R02 | NOT_STARTED | Every request has durable ID/state; crash at any boundary cannot silently double-apply or misreport success. |
| G-R03 | NOT_STARTED | Boot/charging/screen/battery/manual transitions are observed, deterministic and idempotent on fake backend and qualified device. |
| G-R04 | NOT_STARTED | Fresh install/upgrade/rollback staging and reproducible module ZIPs verified without installing on user hardware. |
| G-R05 | NOT_STARTED | Android application acknowledges daemon outcomes, survives lifecycle interruptions, protects credentials and never confuses queued with committed. |
| G-R06 | NOT_STARTED | Devtool is source-pinned, target graph is accurate, legacy final seal accepts native Go, and all mandatory checks are represented. |
| G-R07 | NOT_STARTED | All required checks execute and expose actual test counts and gh output; optimizations prove parity with full gate. |
| G-R08 | NOT_STARTED | Four initial tiles, one deferred recovery tile, all backed by daemon-confirmed state, safe on lockscreen and process death. |
| G-R09 | NOT_STARTED | Mandatory non-empty Go unit/integration, race, fuzz corpus, and fault-injection suites with precise coverage reporting. |
| G-R10 | NOT_STARTED | Real XBS activities/test APK run on fresh isolated API36 emulator with signed evidence, no live root or physical-device claims. |
| G-R11 | NOT_STARTED | Read-only physical inspection first, then explicitly approved reversible tests, then isolated destructive effects on disposable hardware only. |
| G-R12 | NOT_STARTED | Negative tests demonstrate bounded failures, unsafe inputs refuse, performance budgets are measured, and diagnostics remain redacted. |
| G-R13 | NOT_STARTED | All ledger items and required test cases accepted with exact source/bytes/signing/emulator/physical distinctions and safe install commands. |

**Change history:** 2026-11-12 — Source reconciliation and ledger created; all findings open, all tests not run.  
**Retired claims:** HIST-01 daemon missing tuning; HIST-02 `.devtool-wrapper.lock` treated as missing version pin.  
**Current blockers:** No new XBS commits since audited baseline; sole hosted CI run had Go job success and Android job skipped; no rooted or API36 emulator qualification.  
