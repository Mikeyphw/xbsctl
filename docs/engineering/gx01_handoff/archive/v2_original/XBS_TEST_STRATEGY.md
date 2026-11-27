# XBSctl — Comprehensive backend and Android test matrix

**Baseline:** `f048d2d5526a3d0d7632e477b6b4f052e41e30df`  
**Initial status:** NOT_RUN for all proposed test cases, independent of any existing historical Go tests.  
**Matrix:** 131 distinct required/proposed cases. The current repository already has many tests, but existing file presence ≠ this matrix executing. Link existing tests and record observed results as phases progress.  

## Lanes and expected evidence

- **Fast PR / source:** Go unit/fake backend and relevant Android JVM/unit checks with nonzero tests, plus core security-negative cases. Java 21 on hosted CI.
- **Full PR when sensitive:** all Go race/coverage, JVM lint/compile, module validation, Android real Activity instrumentation when Android source/contracts change.
- **API36 hosted emulator:** exact-SHA source + app/test APK IDs, fresh emulator identity, AndroidJUnit/Espresso real journeys and screenshots. NO KernelSU root claims.
- **Periodic full and release:** full backend, app, QS, identity, fuzz/adversarial/performance, API26/34 shards and replay/recovery matrices.
- **Physical root:** separate user-authorized, initially read-only and then safe reversible tests; disruptive tuning reserved for disposable hardware.

**Minimum receipt:** source SHA, workflow/attempt, test name+count started/completed/passed/failed/skipped, runner/device identity, app/test APK SHA256+signer+package, stage outcome, failure category, sanitized logs, screenshots hashes where relevant, explicit `physical_device_qualified:false` and `signed_release_qualified:false` for emulator.

## Tests by suite

| Suite | Cases | Host lane |
|---|---:|---|
| AJ | 19 | ANDROID_JVM_HOSTED |
| BE | 22 | GO_UNIT_HOSTED |
| BR | 9 | GO_BRIDGE_HOSTED |
| CI | 16 | CI_AND_MODULE_HOSTED |
| EM | 24 | ANDROID_EMULATOR_HOSTED |
| FX | 20 | GO_FAKE_BACKEND_HOSTED |
| PF | 8 | PERFORMANCE_REGRESSION |
| RD | 13 | ANDROID_PHYSICAL_OPT_IN |

## Full scenario inventory

### AJ — 19 scenarios

| ID | Test case | Fixture | Required assertion | Phases | Status |
|---|---|---|---|---|---|
| AJ-001 | XbsCommandSpec validation | Dangerous/invalid/freeform command fixture | Rejected before root shell execution | R05 | NOT_RUN |
| AJ-002 | XbsConfigTransaction staging and rollback | Fake file tree and errors at each promotion step | Atomic old/new result or recovery-required | R05 | NOT_RUN |
| AJ-003 | Queued reload vs applied result | Fake controller queued then failed ack | Configuration never marked fully committed | R05 | NOT_RUN |
| AJ-004 | PendingCommandStore process recreation | In-memory map dropped but durable record retained | Owned command not blindly rerun | R05 | NOT_RUN |
| AJ-005 | Root process tree termination parser | Fake pid file/identity and descendant tree | Only matching owned process targeted | R05 | NOT_RUN |
| AJ-006 | RootCommandService FGS denial and timeout | Fake service launches/rejects | Truthful failed and cancellation state | R05 | NOT_RUN |
| AJ-007 | Tasker Keystore signer and pin matrix | Valid/expired/reinstalled/different signer fixtures | Unauthorized config rejected | R05 | NOT_RUN |
| AJ-008 | Tasker request validation and replay | Malformed fields, oversized bundle, reused nonce | Strict refusal and no duplicate action | R05 | NOT_RUN |
| AJ-009 | Tasker sender identity API26/API34 | Synthetic sender UID/package availability | Platform-specific trust rules enforced | R05, R10 | NOT_RUN |
| AJ-010 | Tasker condition and action completion | Fake daemon pending/failed/ok outcomes | Tasker only gets RESULT_OK for observed applied state | R05 | NOT_RUN |
| AJ-011 | EventBridgePolicy state matrix | stale/duplicate/bad MAC/unavailable | Authenticated, fallback, blocked statuses correct | R05 | NOT_RUN |
| AJ-012 | Config event draft validator | Invalid core, path, policy, name and template | Fields blocked with visible message | R05 | NOT_RUN |
| AJ-013 | Essentials file protection | Normal, wrong path, symlink and override | No unapproved critical package change | R01, R05 | NOT_RUN |
| AJ-014 | Room schema and history retention | Prebuilt database v1/v2 and history records | No data loss, stable indexes and cleanup | R05, R10 | NOT_RUN |
| AJ-015 | UI policy state reducers | Fake online/offline/applying/failure status | Every view gets consistent user-facing state | R05 | NOT_RUN |
| AJ-016 | Support bundle redaction | Credentials/paths/device identifiers in fixture | No sensitive value in report | R05, R12 | NOT_RUN |
| AJ-017 | QS controller rapid tap reducer | 30 concurrent quick taps | Single desired state transition and correct last status | R08 | NOT_RUN |
| AJ-018 | QS tile state model and id mapping | active/inactive/unavailable/recovery-required | Proper tile state/subtitle and no false applied | R08 | NOT_RUN |
| AJ-019 | Gradle real test task graph | JDK21 AGP9.3.1 testBuildType release fixture | Correct real variant and count, no alias fake pass | R00, R07 | NOT_RUN |

### BE — 22 scenarios

| ID | Test case | Fixture | Required assertion | Phases | Status |
|---|---|---|---|---|---|
| BE-001 | Config default load and schema | Temporary isolated filesystem | Valid v2 defaults and expected paths only | R00, R02 | NOT_RUN |
| BE-002 | Corrupt JSON and empty config | Empty, truncated, malformed and array JSON files | No daemon mutation and stable machine-readable parse failure | R02 | NOT_RUN |
| BE-003 | Invalid config value matrices | Wrong types, unsupported choices and out-of-range values | Fail closed with exact field error | R02 | NOT_RUN |
| BE-004 | Invalid config paths and symlink parent | Temp tree with symlinks and nonregular nodes | Reject without writing outside fixture | R01, R02 | NOT_RUN |
| BE-005 | Config save atomic interruption | Fault injection at write, sync and rename | Old or complete new configuration, never torn | R02, R12 | NOT_RUN |
| BE-006 | Load/reload schema parity | Same invalid config used during startup and reload | Both refuse identically and preserve current state | R02 | NOT_RUN |
| BE-007 | Configuration template merge | Built-in/custom templates with overlapping fields | Deterministic precedence and no missing mandatory keys | R03 | NOT_RUN |
| BE-008 | Header delay/dry-run precedence | Synthetic CLI override + config values | Exact effective delay/dry-run policy reported | R03 | NOT_RUN |
| BE-009 | Event name and input boundary fuzz | Empty, Unicode, separators, very long event names | No crash/traversal/invalid accepted input | R02, R12 | NOT_RUN |
| BE-010 | CLI error code matrix | Missing config, parse errors, invalid event/args, runtime failure | Exact documented exit code and machine JSON | R02 | NOT_RUN |
| BE-011 | CLI JSON contract golden | Known success/failure snapshots | Stable schema fields and no ambiguous success | R02, R09 | NOT_RUN |
| BE-012 | Controller --wait success and failure | Simulated ack applied/failed/timeout | Accurate completion and exit code | R02, R05 | NOT_RUN |
| BE-013 | Queue FIFO concurrent producers | 50 concurrent staged requests and deterministic clock | All unique IDs processed once in intended order | R02 | NOT_RUN |
| BE-014 | Queue operation crash matrix | Crash before claim, during effects, after persistence, before ack | No duplicate effects and truthful recovery status | R01, R02 | NOT_RUN |
| BE-015 | Queue invalid JSON and path safety | Malformed IDs, symlink .work/.ack and wrong owner | Refused/quarantined and no path escape | R02, R12 | NOT_RUN |
| BE-016 | Daemon SIGTERM and restart recovery | Synthetic state journal and signal test | Restores safely or reports recovery-required | R01, R02 | NOT_RUN |
| BE-017 | Daemon concurrent profiles | Two overlapping profiles targeting same sysfs | Stops retain still-owned effects | R01, R03 | NOT_RUN |
| BE-018 | Dry run never mutates privileged state | Instrumented fake runner with mutation counter | Zero system writes and explicit simulated status | R01, R03 | NOT_RUN |
| BE-019 | Daemon status corrupt/torn read | Atomic writers and simulated race | No phantom active/healthy state | R02 | NOT_RUN |
| BE-020 | Go race detector on engine/queue/bridge | go test -race with concurrent triggers | Zero data races and nonempty tests | R09 | NOT_RUN |
| BE-021 | Go repeated seeded suites | go test with bounded repeats and seed records | Consistent outputs; flakes retain evidence | R09 | NOT_RUN |
| BE-022 | Backend fuzz JSON and control corpus | Native Go fuzz seed corpus with byte limits | No panics, unsafe allocation or hangs | R09, R12 | NOT_RUN |

### BR — 9 scenarios

| ID | Test case | Fixture | Required assertion | Phases | Status |
|---|---|---|---|---|---|
| BR-001 | Correct signed envelope publication and verify | Private key/fake status/event files | Authenticated payload with expected sequence and digest | R05 | NOT_RUN |
| BR-002 | Key ownership and file mode | Wrong uid/group-readable/symlink key | Hard failure with non-authenticated status | R05, R12 | NOT_RUN |
| BR-003 | Malformed payload and invalid schema | JSON fuzz and oversized envelope | Bounded refusal without panic | R05, R09, R12 | NOT_RUN |
| BR-004 | Sequence replay and stale cursor | Same/lower sequence with valid MAC | Rejected or explicitly duplicate per contract | R05 | NOT_RUN |
| BR-005 | Wrong key or tampered MAC | Byte-flip or mismatched key ID | Rejected with security reason, never fallback as verified | R05 | NOT_RUN |
| BR-006 | Publication fsync/rename failure | Mocked I/O failure on sequence/envelope | Daemon reports error and does not falsely claim bridge fresh | R05 | NOT_RUN |
| BR-007 | Trusted source symlink and mode negatives | Unsafe parent/source transitions | Refused on both preflight and post-open checks | R05, R12 | NOT_RUN |
| BR-008 | Concurrent publisher sequence monotonicity | Two publishers acquiring lock | Unique increasing sequences and no torn envelope | R05, R09 | NOT_RUN |
| BR-009 | Android verifier legacy fallback policy | Contract fixture with missing verifier vs bad MAC | Only permitted availability reason uses fallback | R05, R10 | NOT_RUN |

### CI — 16 scenarios

| ID | Test case | Fixture | Required assertion | Phases | Status |
|---|---|---|---|---|---|
| CI-001 | Devtool wrapper native runner support | Pinned Devtool executable and native Go target | Plan/preflight/invoke exact expected jobs and schema | R06 | NOT_RUN |
| CI-002 | Go CI complete graph | Fresh GitHub run exact SHA | Tests vet contracts final seal module all execute nonempty | R06, R07 | NOT_RUN |
| CI-003 | Android JVM lint debug build graph | Hosted JDK21 AGP9.3.1 Gradle9.7.1 | Expected tasks and exact test counts; not skipped | R00, R07 | NOT_RUN |
| CI-004 | CI affected target shared files | Common schema/toolchain/contract changed | Both dependent Go and Android lanes scheduled | R06, R07 | NOT_RUN |
| CI-005 | CI unknown path conservative fallback | Unknown file ownership or broad framework change | Full validation rather than unsafe skip | R07 | NOT_RUN |
| CI-006 | CI identical run redispatch avoidance | Already-running or successful same SHA workflow | Reused/watched, no duplicate dispatch | R07 | NOT_RUN |
| CI-007 | CI raw check and failed logs display | Fixture real gh command output and ANSI escapes | Actual check lines and meaningful failed logs printed | R07 | NOT_RUN |
| CI-008 | CI artifact source/run mismatch | Wrong commit SHA/run ID/attempt APK | Artifact refused as unqualified | R07, R12 | NOT_RUN |
| CI-009 | CI missing or zero-test lane | Skip or no-tests + fake pass marker | Gate refuses qualified success | R07, R12 | NOT_RUN |
| CI-010 | CI source drift and dirty tree refusal | Modified/unexpected files or checkout changed | Overlay refuses and preserves all unrelated files | R07, R12 | NOT_RUN |
| CI-011 | Module ZIP ELF and reproducibility | Malformed ARM64 ELF, symlink and repeated builds | Wrong runtime refused, identical valid ZIP hashes | R04, R09 | NOT_RUN |
| CI-012 | Module replace failure handling | Rename/file-system failure during staging | Previous stage remains usable and recoverable | R04 | NOT_RUN |
| CI-013 | Workflow mutable action dependency check | Workflow source with @v4 and @main | Fail policy check until immutable SHA used | R07 | NOT_RUN |
| CI-014 | Release package identity/provenance | Signed candidate and source/run manifest | Package/signer/hash/version exact; emulator != release | R13 | NOT_RUN |
| CI-015 | CI targeted/full parity | Same changes both route types | Required full tests preserved with no missing dependency | R07 | NOT_RUN |
| CI-016 | Overlay interrupted worktree restore | Synthetic worktrees and failure injection | Checkout and pre-existing edits preserved | R12 | NOT_RUN |

### EM — 24 scenarios

| ID | Test case | Fixture | Required assertion | Phases | Status |
|---|---|---|---|---|---|
| EM-001 | Emulator identity refuses physical | ADB serial API36 ro.kernel.qemu flag | Disconnected/nonemulator/wrong API safely refused | R10 | NOT_RUN |
| EM-002 | App APK and instrumentation pair identity | Unique debug app/test APK and SHA/cert check | Exactly one matching package and source/run/attempt | R10 | NOT_RUN |
| EM-003 | App starts on fresh offline API36 device | No root, no accounts, no XBS module | MainActivity renders offline and actionable guidance | R10 | NOT_RUN |
| EM-004 | Complete app navigation and back | Visit all screens and return paths | Each screen interactive and correct back stack | R10 | NOT_RUN |
| EM-005 | Configuration create/edit/validate journey | Fake backend with mutable config | Save, error and reload outcome visible | R05, R10 | NOT_RUN |
| EM-006 | Custom event editor journey | Add, edit, duplicate, delete synthetic profile | Correct persistence/validation and no dropped changes | R03, R10 | NOT_RUN |
| EM-007 | Essentials editor protected path journey | Empty/invalid/valid list fixture | Reject unsafe input and explain why | R01, R10 | NOT_RUN |
| EM-008 | Command center pending/failed/ok journey | Fake daemon delayed/failing operation | UI does not report queued as applied | R05, R10 | NOT_RUN |
| EM-009 | Foreground service denial and recovery | Simulated Android lifecycle restrictions | Recovery/error message displayed without ANR | R05, R10 | NOT_RUN |
| EM-010 | Tasker action intent hostile fixture | Untrusted sender/config with missing/invalid signature | No root execution or premature Tasker success | R05, R10 | NOT_RUN |
| EM-011 | Tasker edit/activity return path | Supported mock Tasker host and app chooser | Valid bounded config and correct return/result | R05, R10 | NOT_RUN |
| EM-012 | Notification cancel action and service lifecycle | Fake long-running command | Cancel status and notification consistent | R05, R10 | NOT_RUN |
| EM-013 | Room real v1-to-v2 migration | Room fixture DB and androidx.test | Data and index preserved | R05, R10 | NOT_RUN |
| EM-014 | Process death separate from recreation | Host kills app between two instrument runs | Journal persists; no duplicate command after restart | R02, R05, R10 | NOT_RUN |
| EM-015 | App rotation and process restoration | Rotate with editor open and pending request | No data loss/duplicate mutation | R05, R10 | NOT_RUN |
| EM-016 | SystemUI status/navigation bar layout | Emulator screenshots at 360dp and 393dp | No clipping or obscured buttons/insets | R10 | NOT_RUN |
| EM-017 | Dark/light, locale and RTL coverage | Synthetic fixture on multiple UI modes | No clipped inaccessible controls, correct translations | R10 | NOT_RUN |
| EM-018 | Accessibility touch/heading/navigation | Espresso accessibility checks | Actual focus/labels/targets pass | R10 | NOT_RUN |
| EM-019 | QS TileService callback on unavailable backend | Nonroot fake state with broadcast updates | Disabled/unavailable state; no root/false success | R08 | NOT_RUN |
| EM-020 | QS rapid taps and process recreation | Simulated SystemUI interactions, process kill | No duplicate commands and correct persisted state | R08 | NOT_RUN |
| EM-021 | QS unlock/confirmation gate | Lockscreen or simulated locked state | Sensitive action requires unlock/explicit confirmation | R08 | NOT_RUN |
| EM-022 | App screenshots and evidence redaction | Real screen images, PNG validator | Unique dimensions/hash, no user secrets or raw private log | R10, R12 | NOT_RUN |
| EM-023 | API26 compatibility smoke | Hosted API26 x86_64 emulator fixture | App launches, Tasker fallback and no unsupported call | R10 | NOT_RUN |
| EM-024 | API34 sender identity and FGS smoke | Hosted API34 fixture | No API-specific SecurityException, correct sender rules | R10 | NOT_RUN |

### FX — 20 scenarios

| ID | Test case | Fixture | Required assertion | Phases | Status |
|---|---|---|---|---|---|
| FX-001 | CPU governor round trip | Fake sysfs per-core original governor | Exact restore for modified cores | R01, R09 | NOT_RUN |
| FX-002 | CPU offline preserves cpu0 | Fake online map and unsafe cpu0 request | Never offlines cpu0 and refuses unsupported core | R01 | NOT_RUN |
| FX-003 | Partial CPU rollback write failure | Fail second sysfs write then fail restore | Unresolved journal persists and no rollback PASS | R01 | NOT_RUN |
| FX-004 | Original state read failure | Fake Read rejects governor/prop/Wi-Fi/GMS | Effect not changed and failure identified | R01 | NOT_RUN |
| FX-005 | GMS initially enabled vs disabled | Two fake package-manager states | Restore exact original state; no spurious enable | R01 | NOT_RUN |
| FX-006 | GMS disable succeeds force-stop fails | Inject second-stage error | Snapshot retained and rollback report truthful | R01 | NOT_RUN |
| FX-007 | Wi-Fi state matrix | Initially on/off, missing status, disable failing | Exact restore or refusal on uncertain state | R01 | NOT_RUN |
| FX-008 | Low-RAM property matrix | Existing missing/false/true and resetprop fail | Exact original value/presence or error | R01 | NOT_RUN |
| FX-009 | App suspend exact ownership | Pre-suspended and active apps, multiple users | Only newly suspended owned apps unsuspended | R01 | NOT_RUN |
| FX-010 | Apps changed after profile start | Install/remove/mutate lists post-start | Restore exact originally owned package set | R01 | NOT_RUN |
| FX-011 | Essentials allowlist precedence | System packages in denylist plus malicious template | Critical protected packages never suspended/killed | R01 | NOT_RUN |
| FX-012 | Process PID reuse | Fake pidof and proc stat different process starttime | Never renice unrelated restarted process | R01 | NOT_RUN |
| FX-013 | Process priority maintenance | Repeated timer/active profile and fake pids | Idempotent intended nice values only | R01, R03 | NOT_RUN |
| FX-014 | Force-stop irreversible truth | Kill package via fake shell | Status flags nonreversible action, no fake rollback | R01 | NOT_RUN |
| FX-015 | Doze force-idle failure | Fake dumpsys unsupported/malformed response | Clear failed effect, no claimed applied state | R01 | NOT_RUN |
| FX-016 | Effect overlap and precedence | Two events controlling same resource | Per-effect reference/desired state reconciled | R01, R03 | NOT_RUN |
| FX-017 | Crash before durable snapshot | Inject stop after prepare vs first mutation | No state mutation without recovery journal | R01, R02 | NOT_RUN |
| FX-018 | Rule file input limit and invalid packages | Oversized, duplicate, malicious lines | No unsafe package handling or resource exhaustion | R01 | NOT_RUN |
| FX-019 | Automatic charging and display graph | Deterministic synthetic signal source | Correct priority/debounce/reversal transitions | R03 | NOT_RUN |
| FX-020 | Boot/quit/low-power graph | Fake boot battery and unplug sequence | No missed boot or spurious low-power activation | R03 | NOT_RUN |

### PF — 8 scenarios

| ID | Test case | Fixture | Required assertion | Phases | Status |
|---|---|---|---|---|---|
| PF-001 | Application cold startup duration | Bounded API36 fresh device startup loops | Measured median/p95 and regressions flagged against approved baseline | R10, R12 | NOT_RUN |
| PF-002 | Root command acknowledged latency | Fake daemon slow/fast response histogram | Bounded latency/error metrics and no false timeout completion | R05, R12 | NOT_RUN |
| PF-003 | App ANR and StrictMode stability | Instrumented routing/settings/history stress | Zero ANRs and forbidden main-thread disk/root I/O | R05, R12 | NOT_RUN |
| PF-004 | Foreground service leak and cancellation | 100 synthetic command starts/stops | No leftover worker/thread/process and journal consistent | R05, R12 | NOT_RUN |
| PF-005 | QS refresh polling and wakeups | Emulator and safe physical read-only measurement | No continuous high-rate polls/wakelocks; measured budget | R08, R12 | NOT_RUN |
| PF-006 | Long-running daemon resource use | Fake event input for extended test session | Bounded CPU/memory/FD and no journal growth leak | R03, R12 | NOT_RUN |
| PF-007 | UI screenshot dimensions and geometry | Compare 360dp/393dp layouts at normal font scale | No text clipping/overlap, real screen images and metadata | R10, R12 | NOT_RUN |
| PF-008 | Root effects genuine battery impact | Consented isolated lab or monitored device | Before/after data and confounders recorded, no invented savings | R11, R12 | NOT_RUN |

### RD — 13 scenarios

| ID | Test case | Fixture | Required assertion | Phases | Status |
|---|---|---|---|---|---|
| RD-001 | Physical read-only acceptance | User-authorized Android16 API36 rooted device | API, kernelSU/Magisk version and module status only; no mutation | R11 | NOT_RUN |
| RD-002 | Physical module install manifest | Explicit authorized module ZIP and backup | Exact artifact hash, install success and recovery record | R04, R11 | NOT_RUN |
| RD-003 | Actual daemon boot state | Device restart with non-disruptive profile | Daemon comes up, config exists, boot event observed | R03, R11 | NOT_RUN |
| RD-004 | Actual charging/unplugging state | Charger attach/detach after baseline snapshot | Expected transitions and no duplicate effect | R03, R11 | NOT_RUN |
| RD-005 | Actual screen on/off state | Physical display and timeout | Corresponding event state maintained and reversed | R03, R11 | NOT_RUN |
| RD-006 | Root controller end-to-end command | Deliberately no-op or reversible manual profile | CLI and app observed daemon-confirmed status | R02, R11 | NOT_RUN |
| RD-007 | Physical Tasker end-to-end | User-approved Tasker action and condition | Completion and state match exact command outcome | R05, R11 | NOT_RUN |
| RD-008 | Physical QS tile integration | Add native SystemUI tiles on actual OEM ROM | Correct labels, lockscreen, off/on/update without app | R08, R11 | NOT_RUN |
| RD-009 | Physical daemon forced stop recovery | Consented no-op or reversible effect | Journal recovers, no duplicate operation | R01, R11 | NOT_RUN |
| RD-010 | Physical root process termination | Dedicated disposable child process | Cancel/timeout terminates only owned process tree | R05, R11 | NOT_RUN |
| RD-011 | Physical reversible effect restoration | Read-only baseline plus safe approved effect | Exact before/after verified and cleanup pass | R01, R11 | NOT_RUN |
| RD-012 | Disposable high-impact effects lab | Isolated sacrificial device with explicit permission | CPU/GMS/Wi-Fi stress verified and restored, no primary-phone run | R01, R11 | NOT_RUN |
| RD-013 | Physical uninstall and upgrade preservation | Explicit consent and exact module version | User config preserved/upgrades; restore/uninstall reports truth | R04, R11 | NOT_RUN |

## Anti-false-PASS contracts

1. Nonempty test counts and exact executed method identifiers required. A zero-test JVM/Go/instrumentation lane fails.
2. `testBuildType 'release'` and synthetic `testDebugUnitTest` alias must be resolved before claiming debug instrumentation.
3. A Cinderbox source gate or passing Cinderbox run is reference evidence, **not** an XBS app test result.
4. `go test -race` and fuzz tests run only on synthetic/mock fixtures in hosted CI. Real root integration is opt-in.
5. Activity recreation is not proof of process death. A host-side external kill and two-stage retained marker/command journal test is required.
6. API36 emulator results can qualify real UI and SystemUI integration only where actually observable; x86_64 cannot validate ARM64 KernelSU/Magisk binary execution.
7. Never blindly retry a failed CI test to make green. Every retry/attempt remains recorded and analyzed.
8. No screenshot/golden PASS without validating actual device capture metadata, dimensions and SHA. Do not manufacture a passing image.
9. Event/rollback invariants are more important than code-coverage percentage alone. No release qualification while recovery failures remain unresolved.
10. Statuses do not advance until independent evidence has been attached to the ledger against the exact commit.
