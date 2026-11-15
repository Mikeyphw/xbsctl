# GX01 — 26 audited findings

No finding was promoted to FIXED or ACCEPTED without exact implementing commit, named assertion and independent closure receipt. Historical resolutions remain unmodified.

| Finding | Severity | Original issue | Source path | Closure |
|---|---|---|---|---|
| CODE-01 | P0 | Rollback errors discarded and originals lost on failure | `internal/tuning/tuning.go` | OPEN |
| CODE-02 | P0 | Package suspension does not preserve per-package prior state | `internal/tuning/tuning.go` | OPEN |
| CODE-03 | P1 | Configuration reload is queued, not daemon-confirmed | `android-app/app/src/main/java/io/github/mikeceead/xbscompanion/XbsConfigTransaction.java` | OPEN |
| CODE-04 | P0 | Durable state persists after privileged effect commit window | `internal/daemon/daemon.go` | OPEN |
| CODE-05 | P1 | Missing module initialization helper xbs-paths.sh | `module/customize.sh` | OPEN |
| CODE-06 | P1 | Authenticated bridge publication error ignored | `internal/daemon/daemon.go` | OPEN |
| CODE-07 | P1 | Automatic boot/charging/screen/low-power profiles not wired | `internal/daemon/daemon.go` | OPEN |
| CODE-08 | P1 | Controller error-code expression combines with 6 | `internal/control/control.go` | OPEN |
| CODE-09 | P0 | Queue post-effect pre-ack crash can replay request | `internal/control/inbox.go` | OPEN |
| CODE-10 | P0 | Essentials allowlist not applied to tuning package filter | `internal/tuning/tuning.go` | OPEN |
| CODE-11 | P1 | Initial daemon config load does not validate semantic schema | `internal/config/config.go` | OPEN |
| CODE-12 | P2 | Module update JSON version and upstream source mismatch | `module/update.json` | OPEN |
| CODE-13 | P0 | Original GMS/WiFi/lowRAM probe errors ignored | `internal/tuning/tuning.go` | OPEN |
| CODE-14 | P1 | Inbox request ID/path and file trust boundary not strict | `internal/control/inbox.go` | OPEN |
| CODE-15 | P1 | FinalSeal handoff asserts old command-runner structure | `internal/module/module.go` | OPEN |
| CODE-16 | P2 | StageModule deletes previous destination before replacement | `internal/module/module.go` | OPEN |
| CODE-17 | P1 | Dry-run may advertise state as applied without physical mutation | `internal/tuning/tuning.go` | OPEN |
| INFRA-01 | P0 | Incorrect standalone Gradle .devtool.toml path | `android-app/build.gradle` | OPEN |
| INFRA-02 | P0 | Stale Phase31 918-file/237-test monorepo contract | `android-app/tools/phase31_release_gate.py` | OPEN |
| INFRA-03 | P1 | Android job opt-in and not executed in sole historical XBS workflow | `.github/workflows/devtool-xbs-ci.yml` | OPEN |
| INFRA-04 | P1 | Android build testBuildType release and synthetic debug unit-test alias | `android-app/app/build.gradle` | OPEN |
| INFRA-05 | P1 | Devtool target inputs/outputs double-prefix and artifact verify disabled | `.devtool.toml` | OPEN |
| INFRA-06 | P1 | Devtool ci-module omits vet, contracts, final seal | `.devtool.toml` | OPEN |
| INFRA-07 | P1 | Mutable workflow uses and no hosted pinned Devtool runtime | `.github/workflows/devtool-xbs-ci.yml` | OPEN |
| TEST-01 | P1 | No mandatory actual hosted XBS instrumentation/emulator lane | `.github/workflows/devtool-xbs-ci.yml` | OPEN |
| TEST-02 | P1 | Opt-in root integration suite not represented by hosted green | `internal/tuning/root_integration_test.go` | OPEN |
