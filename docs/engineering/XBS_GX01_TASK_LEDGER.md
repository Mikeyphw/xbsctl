# GX01 — original roadmap task reconciliation

**Local status:** implemented and independently validated; new GX01 hosted CI not yet executed.

Original tasks: **178** · status counts: `{'IN_PROGRESS': 113, 'NOT_STARTED': 65}` · accepted: **0**.
Original phase acceptance gates remain independent; observed cumulative HEAD is not an introducing commit.
The seven formerly HOSTED_VERIFIED original workstreams have no original-task-level named receipt in the handoff: downgraded to IN_PROGRESS, with history preserved.

| Task | Original acceptance requirement | Tracking | Introducing commit | Original gate | GX01 closure |
|---|---|---|---|---|---|
| R00-01 | Record source SHAs, branch, Go modules, Android modules, scripts and the originating audit findings. | IN_PROGRESS | UNMAPPED | G-R00 | OPEN |
| R00-02 | Root Android build reads the repo-root .devtool.toml and rejects missing/incorrect path with an actionable error. | IN_PROGRESS | UNMAPPED | G-R00 | OPEN |
| R00-03 | Historical apps/xbs root assumptions are replaced with verified standalone paths without weakening security checks. | NOT_STARTED | UNMAPPED | G-R00 | OPEN |
| R00-04 | Manifest is regenerated from the intended source tree with exact size/path/hash validation and historical manifest preserved. | NOT_STARTED | UNMAPPED | G-R00 | OPEN |
| R00-05 | Relevant checks are ported or replaced by executable Go/Android tests; unported safety checks remain BLOCKED, not PASS. | NOT_STARTED | UNMAPPED | G-R00 | OPEN |
| R00-06 | Release gate has correct test paths, independent signed-candidate checks and a negative test for missing evidence. | NOT_STARTED | UNMAPPED | G-R00 | OPEN |
| R00-07 | Actual release/debug androidTest task names and APK pairing are discovered and recorded; no assumed debug tests. | NOT_STARTED | UNMAPPED | G-R00 | OPEN |
| R00-08 | Report real executed task graph; remove/migrate misleading alias if it mislabels release-only tests. | NOT_STARTED | UNMAPPED | G-R00 | OPEN |
| R00-09 | Hosted compile and environment diagnostics report exact JDK/AGP/Gradle/SDK versions. | NOT_STARTED | UNMAPPED | G-R00 | OPEN |
| R00-10 | List test IDs/counts and prove every selected test suite runs nonzero tests. | NOT_STARTED | UNMAPPED | G-R00 | OPEN |
| R00-11 | Baseline has correct commit SHA, no unreviewed mutation, and explicit not-tested/blocked fields. | IN_PROGRESS | UNMAPPED | G-R00 | OPEN |
| R00-12 | Legacy evidence is archived as historical rather than mistaken for new standalone qualification. | NOT_STARTED | UNMAPPED | G-R00 | OPEN |
| R01-01 | Original state and operation ID become durable before privileged mutation is attempted. | IN_PROGRESS | UNMAPPED | G-R01 | OPEN |
| R01-02 | Planned/applying/applied/restoring/restored/failed/recovery-required are explicit and monotonic as appropriate. | NOT_STARTED | UNMAPPED | G-R01 | OPEN |
| R01-03 | Failed restore returns nonzero and retains unresolved records for later reconciliation. | IN_PROGRESS | UNMAPPED | G-R01 | OPEN |
| R01-04 | Result, CLI status and UI all distinguish full rollback from partial rollback. | IN_PROGRESS | UNMAPPED | G-R01 | OPEN |
| R01-05 | Each file restores its own original value, with failed writes retaining a journal entry. | IN_PROGRESS | UNMAPPED | G-R01 | OPEN |
| R01-06 | Pre-existing suspended packages remain suspended; only XBS-owned suspensions are reversed. | IN_PROGRESS | UNMAPPED | G-R01 | OPEN |
| R01-07 | Built-in essentials cannot be accidentally killed/suspended by denylist or template overrides. | IN_PROGRESS | UNMAPPED | G-R01 | OPEN |
| R01-08 | Failed probe refuses modification; previously disabled GMS remains disabled after rollback. | IN_PROGRESS | UNMAPPED | G-R01 | OPEN |
| R01-09 | Failed probe refuses disabling; off and on prior states are preserved exactly. | IN_PROGRESS | UNMAPPED | G-R01 | OPEN |
| R01-10 | Previous property and presence semantics are retained even if resetprop or lookup fails. | IN_PROGRESS | UNMAPPED | G-R01 | OPEN |
| R01-11 | Unforce and prior state handling are validated on supported devices and unsupported variants fail clearly. | IN_PROGRESS | UNMAPPED | G-R01 | OPEN |
| R01-12 | PID reuse cannot cause a newly started unrelated process to receive old nice values. | IN_PROGRESS | UNMAPPED | G-R01 | OPEN |
| R01-13 | Outcome and user-facing status do not promise restoration of killed processes. | NOT_STARTED | UNMAPPED | G-R01 | OPEN |
| R01-14 | Ending one active profile does not undo effects still needed by a different profile. | IN_PROGRESS | UNMAPPED | G-R01 | OPEN |
| R01-15 | Duplicate equivalent requests do not drift or overwrite original-state values. | NOT_STARTED | UNMAPPED | G-R01 | OPEN |
| R01-16 | Dry run never changes files/PM/system properties and cannot masquerade as physically applied. | NOT_STARTED | UNMAPPED | G-R01 | OPEN |
| R01-17 | Unsafe symlinks, untrusted paths, oversized rules or invalid content refuse before effect application. | NOT_STARTED | UNMAPPED | G-R01 | OPEN |
| R01-18 | Fault injection demonstrates durable unresolved snapshots and no false success. | IN_PROGRESS | UNMAPPED | G-R01 | OPEN |
| R02-01 | Returned exit codes match the declared CLI contract for all known errors. | IN_PROGRESS | UNMAPPED | G-R02 | OPEN |
| R02-02 | Request, ack, journal and parent paths reject symlinks, bad modes and nonregular nodes. | IN_PROGRESS | UNMAPPED | G-R02 | OPEN |
| R02-03 | JSON IDs and commands have bounded grammars and cannot traverse ack directories. | IN_PROGRESS | UNMAPPED | G-R02 | OPEN |
| R02-04 | Queued/claimed/executing/applied/failed/recovery required are durably distinguishable. | IN_PROGRESS | UNMAPPED | G-R02 | OPEN |
| R02-05 | A reused .work command cannot re-apply already committed effects after lost ack. | IN_PROGRESS | UNMAPPED | G-R02 | OPEN |
| R02-06 | Multiple control writers do not race on state files or privilege effects. | IN_PROGRESS | UNMAPPED | G-R02 | OPEN |
| R02-07 | Same schema, ranges and semantic constraints apply at both entrypoints. | IN_PROGRESS | UNMAPPED | G-R02 | OPEN |
| R02-08 | No healthy/ready response is published while unapplied recovery work exists. | IN_PROGRESS | UNMAPPED | G-R02 | OPEN |
| R02-09 | Restart failure retains error and original journal; subsequent operation does not hide it. | IN_PROGRESS | UNMAPPED | G-R02 | OPEN |
| R02-10 | Timeouts preserve unknown-outcome state until observed; no blind redispatch. | IN_PROGRESS | UNMAPPED | G-R02 | OPEN |
| R02-11 | Truncated/invalid status never causes optimistic transition or false acknowledgement. | IN_PROGRESS | UNMAPPED | G-R02 | OPEN |
| R02-12 | SIGTERM/SIGINT/HUP paths have explicit fail-closed restoration and service lifecycle reporting. | IN_PROGRESS | UNMAPPED | G-R02 | OPEN |
| R02-13 | Consumers receive stable machine-readable states and exact error codes. | IN_PROGRESS | UNMAPPED | G-R02 | OPEN |
| R03-01 | First boot applies only configured boot actions and records outcome. | IN_PROGRESS | UNMAPPED | G-R03 | OPEN |
| R03-02 | Charging transition and disconnection deterministically reconcile desired profiles. | IN_PROGRESS | UNMAPPED | G-R03 | OPEN |
| R03-03 | Screen state changes apply and remove configured profile effects without app dependency. | IN_PROGRESS | UNMAPPED | G-R03 | OPEN |
| R03-04 | Boundary flapping is debounced and thresholds are explicitly validated. | IN_PROGRESS | UNMAPPED | G-R03 | OPEN |
| R03-05 | Relevant screen/low-power actions respect charging policy and a documented priority order. | IN_PROGRESS | UNMAPPED | G-R03 | OPEN |
| R03-06 | Runtime policy honors validated config values and CLI overrides have documented precedence. | IN_PROGRESS | UNMAPPED | G-R03 | OPEN |
| R03-07 | Quit never abandons unrecovered effects and leaves truthful state. | IN_PROGRESS | UNMAPPED | G-R03 | OPEN |
| R03-08 | CLI, Tasker, app and QS tiles share the same profile grammar and state model. | NOT_STARTED | UNMAPPED | G-R03 | OPEN |
| R03-09 | Remove only effects no longer requested by any active event. | IN_PROGRESS | UNMAPPED | G-R03 | OPEN |
| R03-10 | Restart does not fabricate new charging/screen transitions or assume events applied. | IN_PROGRESS | UNMAPPED | G-R03 | OPEN |
| R03-11 | Repeated events do not duplicate actions; startup reads current observable state. | IN_PROGRESS | UNMAPPED | G-R03 | OPEN |
| R03-12 | Status reports trigger, requested profile, final actual effects and last error. | IN_PROGRESS | UNMAPPED | G-R03 | OPEN |
| R03-13 | Synthetic power/display/battery inputs exercise every transition without root. | IN_PROGRESS | UNMAPPED | G-R03 | OPEN |
| R04-01 | Fresh module installation initializes configuration through a verified bundled mechanism. | IN_PROGRESS | UNMAPPED | G-R04 | OPEN |
| R04-02 | Config required by control commands exists and validates before daemon readiness. | IN_PROGRESS | UNMAPPED | G-R04 | OPEN |
| R04-03 | Existing profile/list files remain intact and are migratable. | IN_PROGRESS | UNMAPPED | G-R04 | OPEN |
| R04-04 | ELF machine, mode, hashes and declared package identity match. | IN_PROGRESS | UNMAPPED | G-R04 | OPEN |
| R04-05 | Failed rename/validation cannot destroy the previously good stage. | IN_PROGRESS | UNMAPPED | G-R04 | OPEN |
| R04-06 | No traversal/symlink/duplicate unsafe members; two independent outputs hash-equal. | IN_PROGRESS | UNMAPPED | G-R04 | OPEN |
| R04-07 | Version/name/source/release links agree or updates are disabled safely. | IN_PROGRESS | UNMAPPED | G-R04 | OPEN |
| R04-08 | Correct script permissions, environment and entrypoints verified. | IN_PROGRESS | UNMAPPED | G-R04 | OPEN |
| R04-09 | Evidence documents backup/restoration and no unrequested installation. | IN_PROGRESS | UNMAPPED | G-R04 | OPEN |
| R04-10 | The latest successful qualified module ZIP is resolved by exact run/artifact and verified hash. | IN_PROGRESS | UNMAPPED | G-R04 | OPEN |
| R05-01 | Transactions cannot claim applied until matching request ack and observed config state. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R05-02 | Unknown outcome is not rolled back blindly or labelled committed. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R05-03 | Diagnostics show failed authentication/publication distinctly from unavailable data. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R05-04 | Forged, stale, reordered or wrong-key envelopes cannot enter authenticated state. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R05-05 | Timeout/cancel/process death leaves verified process termination or explicit recovery requirement. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R05-06 | Android FGS denial/timeout/restart is reported and cannot lose owned commands. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R05-07 | StrictMode and responsiveness tests detect accidental UI-thread disk/root work. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R05-08 | Keystore, signer, sender UID and host-pin checks are exercised across APIs. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R05-09 | Tasker never reports success for queued, failed, timed-out or unverified effects. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R05-10 | One tap produces at most one intended command, with bounded worker and refresh behavior. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R05-11 | UI shows pending, running, applied, cancelled, failed, offline and recovery-required. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R05-12 | Actual managed files remain correct under failures and contention. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R05-13 | Exported Tasker components reject unauthorized or malformed callers. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R05-14 | Logs, tokens, filenames and device state are not leaked to hosted artifacts. | IN_PROGRESS | UNMAPPED | G-R05 | OPEN |
| R06-01 | Tests/builds use supported native Go integrations with same semantics as previous jobs. | IN_PROGRESS | UNMAPPED | G-R06 | OPEN |
| R06-02 | Contracts/final-seal commands remain explicit and nonduplicated. | IN_PROGRESS | UNMAPPED | G-R06 | OPEN |
| R06-03 | Target-root-relative scopes identify real files with no doubled android-app prefix. | IN_PROGRESS | UNMAPPED | G-R06 | OPEN |
| R06-04 | Tests vet contracts seal and build prerequisites cannot be bypassed by alternate workflow. | IN_PROGRESS | UNMAPPED | G-R06 | OPEN |
| R06-05 | Independent nodes parallelize only after preflight verifies dependencies and hazards. | IN_PROGRESS | UNMAPPED | G-R06 | OPEN |
| R06-06 | Final seal recognizes native Go runner and fails on missing mandatory jobs. | IN_PROGRESS | UNMAPPED | G-R06 | OPEN |
| R06-07 | Git checkout SHA, installed module identity and CLI schema are verified. | IN_PROGRESS | UNMAPPED | G-R06 | OPEN |
| R06-08 | Unknown/critical ownership forces conservative full validation. | IN_PROGRESS | UNMAPPED | G-R06 | OPEN |
| R06-09 | Source scope, task ordering, read/write hazards are inspectable. | IN_PROGRESS | UNMAPPED | G-R06 | OPEN |
| R06-10 | Missing APK/module artifact is a failure; verified bytes are not by themselves release qualified. | IN_PROGRESS | UNMAPPED | G-R06 | OPEN |
| R06-11 | Already-dispatched attempts are inspected rather than blindly redispatched. | IN_PROGRESS | UNMAPPED | G-R06 | OPEN |
| R06-12 | Hosted-only Gradle rule is tested as a policy contract. | IN_PROGRESS | UNMAPPED | G-R06 | OPEN |
| R07-01 | Stable names and explicit prerequisites replace accidental workflow proliferation. | IN_PROGRESS | UNMAPPED | G-R07 | OPEN |
| R07-02 | Required jobs report observed test counts and artifact identity. | IN_PROGRESS | UNMAPPED | G-R07 | OPEN |
| R07-03 | Source-bound JDK21/AGP9.3.1/Gradle9.7.1 suite cannot be skipped by default. | IN_PROGRESS | UNMAPPED | G-R07 | OPEN |
| R07-04 | All hosted actions use reviewed full commit SHAs with least privilege. | IN_PROGRESS | UNMAPPED | G-R07 | OPEN |
| R07-05 | Affected classification never skips shared-contract or security-sensitive dependencies. | IN_PROGRESS | UNMAPPED | G-R07 | OPEN |
| R07-06 | No duplicated dispatch for the same SHA/attempt; no skipped full audit indefinitely. | IN_PROGRESS | UNMAPPED | G-R07 | OPEN |
| R07-07 | Cache is keyed/segregated so stale code/test outputs cannot qualify a changed source. | NOT_STARTED | UNMAPPED | G-R07 | OPEN |
| R07-08 | Conflicting Go/Android/module work is serialized and unsafe concurrency is refused. | IN_PROGRESS | UNMAPPED | G-R07 | OPEN |
| R07-09 | Redundant tasks removed only when dependencies guarantee identical coverage. | NOT_STARTED | UNMAPPED | G-R07 | OPEN |
| R07-10 | Emulator acquisition only happens in actual emulator lanes. | IN_PROGRESS | UNMAPPED | G-R07 | OPEN |
| R07-11 | Launcher displays raw/decoded check outputs with ANSI handling and meaningful errors. | IN_PROGRESS | UNMAPPED | G-R07 | OPEN |
| R07-12 | It watches observed runs and reports attempts without creating duplicate runs. | IN_PROGRESS | UNMAPPED | G-R07 | OPEN |
| R07-13 | A workflow succeeded does not qualify an artifact if expected job or evidence is missing. | IN_PROGRESS | UNMAPPED | G-R07 | OPEN |
| R07-14 | Wrong SHA, missing tests, skipped checks, stale artifacts and duplicate runs all fail closed. | IN_PROGRESS | UNMAPPED | G-R07 | OPEN |
| R07-15 | Job count is optimized only after required policy coverage is proved equivalent. | NOT_STARTED | UNMAPPED | G-R07 | OPEN |
| R08-01 | All tiles use one authoritative daemon response and event bridge, not duplicated command logic. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R08-02 | Manual profile activation toggles only after confirmed daemon success. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R08-03 | Pausing/restoring respects outstanding effects and durable recovery journal. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R08-04 | Compact UI exposes built-in/custom profiles with real active status and correct back/navigation. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R08-05 | Safe mode requires verified restoration and deliberate exit policy. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R08-06 | Unlock and confirmation are mandatory, action never fires destructive restore directly from QS. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R08-07 | Exported TileService declares BIND_QUICK_SETTINGS_TILE and approved intents only. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R08-08 | Active/inactive/unavailable plus subtitle distinguish applying/recovery required. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R08-09 | Tiles update through appropriate TileService listening callbacks. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R08-10 | No continuous polling or blocking binder callbacks. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R08-11 | Repeated taps create at most one actual transition per requested state. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R08-12 | Sensitive operations use platform unlock request or in-app confirmation. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R08-13 | Supported devices can request tile addition; unsupported releases show instructions. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R08-14 | Tests cover tile lifecycle, process death, no root, errors and external state; physical acceptance is deferred to R11. | NOT_STARTED | UNMAPPED | G-R08 | OPEN |
| R09-01 | File/PM/dumpsys/resetprop/process/charging/display responses are injectable without real root. | IN_PROGRESS | UNMAPPED | G-R09 | OPEN |
| R09-02 | Invalid types, ranges, names, symlinks and atomic save errors refuse deterministically. | IN_PROGRESS | UNMAPPED | G-R09 | OPEN |
| R09-03 | CPU, process, app, GMS, Wi-Fi, Doze and low_ram simulated state returns exactly. | IN_PROGRESS | UNMAPPED | G-R09 | OPEN |
| R09-04 | Every mutation boundary preserves a durable recovery path. | IN_PROGRESS | UNMAPPED | G-R09 | OPEN |
| R09-05 | Race detector and restart simulation show no duplicate effect. | IN_PROGRESS | UNMAPPED | G-R09 | OPEN |
| R09-06 | Malformed and oversized input never causes panic or unbounded resource use. | IN_PROGRESS | UNMAPPED | G-R09 | OPEN |
| R09-07 | Wrong architecture, symlink, zip slip, duplicate names and hash mismatch refuse. | IN_PROGRESS | UNMAPPED | G-R09 | OPEN |
| R09-08 | Races/flake detection uses bounded test repetitions and records failures, not retries as fake PASS. | IN_PROGRESS | UNMAPPED | G-R09 | OPEN |
| R09-09 | JSON/text schema snapshots verify backward compatibility, errors and success boundaries. | IN_PROGRESS | UNMAPPED | G-R09 | OPEN |
| R09-10 | High-risk engine/queue/bridge paths have explicit branch/fault coverage and missing-cases ledger. | IN_PROGRESS | UNMAPPED | G-R09 | OPEN |
| R09-11 | Ordinary hosted test cannot toggle hardware or GMS; rooted tests require separate acknowledgement. | IN_PROGRESS | UNMAPPED | G-R09 | OPEN |
| R10-01 | Hosted KVM/API36 Pixel6 setup adapted to XBS JDK21/Gradle9.7.1/AGP9.3.1. | IN_PROGRESS | UNMAPPED | G-R10 | OPEN |
| R10-02 | Debug and instrumentation binaries belong to same source/run/attempt. | IN_PROGRESS | UNMAPPED | G-R10 | OPEN |
| R10-03 | Verify ro.kernel.qemu=1, API, unique ADB target and no existing XBS install. | IN_PROGRESS | UNMAPPED | G-R10 | OPEN |
| R10-04 | No Magisk/KernelSU or actual system tunables are required or claimed in emulator. | IN_PROGRESS | UNMAPPED | G-R10 | OPEN |
| R10-05 | Every screen is opened, scrolled, interacted with and returned from. | NOT_STARTED | UNMAPPED | G-R10 | OPEN |
| R10-06 | Valid and invalid edits, rollback prompt, error feedback and state round trip. | NOT_STARTED | UNMAPPED | G-R10 | OPEN |
| R10-07 | Valid signed fixture and malicious intents are distinguished. | NOT_STARTED | UNMAPPED | G-R10 | OPEN |
| R10-08 | External host restarts app between test stages and verifies persisted outcome. | NOT_STARTED | UNMAPPED | G-R10 | OPEN |
| R10-09 | Data/index preservation and zero main-thread DB/root calls proven. | NOT_STARTED | UNMAPPED | G-R10 | OPEN |
| R10-10 | At multiple dp widths, theme, locale, rotation, status/nav bars; screenshots are non-secret and source bound. | NOT_STARTED | UNMAPPED | G-R10 | OPEN |
| R10-11 | APK identity, tests started/passed, instrument exit, screenshot dimensions/hash, failure categories recorded. | IN_PROGRESS | UNMAPPED | G-R10 | OPEN |
| R10-12 | Primary API36 lane is authoritative; minSdk and Tasker identity coverage have separate gates. | NOT_STARTED | UNMAPPED | G-R10 | OPEN |
| R10-13 | Receipts explicitly say emulator_only, root_effects_not_tested, physical_qualified=false. | IN_PROGRESS | UNMAPPED | G-R10 | OPEN |
| R11-01 | API36 ABI root manager package signature and module version captured read-only. | NOT_STARTED | UNMAPPED | G-R11 | OPEN |
| R11-02 | Installation is not initiated by source validation or ordinary emulator jobs. | NOT_STARTED | UNMAPPED | G-R11 | OPEN |
| R11-03 | Trigger observations and restoration evidence include timestamps and state deltas. | NOT_STARTED | UNMAPPED | G-R11 | OPEN |
| R11-04 | One device action shows consistent command/state receipt in all components. | NOT_STARTED | UNMAPPED | G-R11 | OPEN |
| R11-05 | OEM-specific SystemUI behavior is documented separately. | NOT_STARTED | UNMAPPED | G-R11 | OPEN |
| R11-06 | Pre-state/post-state snapshots and cleanup pass with exact per-effect ownership. | NOT_STARTED | UNMAPPED | G-R11 | OPEN |
| R11-07 | High-impact effects excluded from unattended ordinary device smoke. | NOT_STARTED | UNMAPPED | G-R11 | OPEN |
| R11-08 | Kill/cold-restart between stages and check journal before declaring restored. | NOT_STARTED | UNMAPPED | G-R11 | OPEN |
| R11-09 | Uninstall/rollback leaves no unauthorized modifications to system/profile state. | NOT_STARTED | UNMAPPED | G-R11 | OPEN |
| R11-10 | Physical qualification links exact build hash, source SHA, device API and consented test family. | NOT_STARTED | UNMAPPED | G-R11 | OPEN |
| R12-01 | No traversal/symlink escape, panic, resource blowup or partial output qualification. | IN_PROGRESS | UNMAPPED | G-R12 | OPEN |
| R12-02 | Atomic state survives interrupted persistence and reports unresolved recovery. | IN_PROGRESS | UNMAPPED | G-R12 | OPEN |
| R12-03 | Invalid UID/cert/signature fails without command execution. | NOT_STARTED | UNMAPPED | G-R12 | OPEN |
| R12-04 | Wrong key, stale sequence and unsafe filesystem fail closed. | NOT_STARTED | UNMAPPED | G-R12 | OPEN |
| R12-05 | User-controlled profile/file/argument values cannot escape expected argv/path. | IN_PROGRESS | UNMAPPED | G-R12 | OPEN |
| R12-06 | Required test lane refuses instead of uploading fake PASS. | IN_PROGRESS | UNMAPPED | G-R12 | OPEN |
| R12-07 | Downloaded APK/module never qualified against a different commit. | IN_PROGRESS | UNMAPPED | G-R12 | OPEN |
| R12-08 | Source drift refuses and unrelated files/worktrees remain unchanged. | IN_PROGRESS | UNMAPPED | G-R12 | OPEN |
| R12-09 | Bounded startup/root service/QS response and no unbounded resource growth measured. | NOT_STARTED | UNMAPPED | G-R12 | OPEN |
| R12-10 | Backend event watcher and UI tiles do not create continuous high-rate CPU or wakeups. | NOT_STARTED | UNMAPPED | G-R12 | OPEN |
| R12-11 | Redacted report contains no credentials or raw private Android data. | NOT_STARTED | UNMAPPED | G-R12 | OPEN |
| R12-12 | A missing mandatory negative case blocks final gate rather than marking green. | IN_PROGRESS | UNMAPPED | G-R12 | OPEN |
| R13-01 | Ledger records implemented revision, verified tests, unresolved blockers and no silently dropped promises. | NOT_STARTED | UNMAPPED | G-R13 | OPEN |
| R13-02 | Package ID, versionCode, signer, source SHA, run ID/attempt and artifact hashes match. | NOT_STARTED | UNMAPPED | G-R13 | OPEN |
| R13-03 | CI build success cannot be mistaken for signed release or device qualification. | NOT_STARTED | UNMAPPED | G-R13 | OPEN |
| R13-04 | No historical 918/237 contracts are accepted as current evidence. | NOT_STARTED | UNMAPPED | G-R13 | OPEN |
| R13-05 | Build outputs and profiling evidence correspond to exact candidate. | NOT_STARTED | UNMAPPED | G-R13 | OPEN |
| R13-06 | Explicit artifact links and metadata are independently verifiable. | NOT_STARTED | UNMAPPED | G-R13 | OPEN |
| R13-07 | Commands resolve latest successful *qualified* artifact without touching phone automatically. | NOT_STARTED | UNMAPPED | G-R13 | OPEN |
| R13-08 | Users can inspect actual gh checks, source-bound receipts and privacy-safe failure causes. | NOT_STARTED | UNMAPPED | G-R13 | OPEN |
| R13-09 | No changes to affected-selection prevent complete regression qualification. | NOT_STARTED | UNMAPPED | G-R13 | OPEN |
| R13-10 | History remains audit-friendly and corrected audit claims stay retired. | NOT_STARTED | UNMAPPED | G-R13 | OPEN |
| R13-11 | Every critical issue has proof, not merely a fix commit. | NOT_STARTED | UNMAPPED | G-R13 | OPEN |
