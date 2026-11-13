# XBS R04 — Consolidated offline module lifecycle and artifact safety

Base commit: `aba8b6452134ce5d276ee0dbeb752bb6c0f1d8b2` (R03D PR #13).

## Implemented source surface (R04-01 through R04-10)

1. Removed the defunct `xbs-paths.sh` installation dependency. The Go-native `xbs init` and `xbs validate` are both required on boot, preserving existing config and user lists.
2. The installer shell verifies a detached SHA256 seal for all three ARM64 native binaries before granting permissions. There is no Python/jq dependency in the on-device installer.
3. The offline stage transaction validates the complete source tree, checks the three ELF headers plus manifest hashes and layout, preserves the previous stage under `.r04.previous`, and writes fsynced `intent` and completion receipts. Incomplete transactions and preexisting backups block any unsafe overwrite.
4. Legacy R03 Go-native staged modules can be backed up only after separately verifying their three runtime manifest hashes; the old broken update feed is not treated as trusted.
5. Rollback swaps a newly verified staged module back to its verified previous stage, retaining the replaced stage under `.r04.failed`. Finalizing a verified backup requires `I_ACCEPT_VERIFIED_STAGE`.
6. Module ZIP verification rejects traversal, absolute paths, backslash paths, case-insensitive duplicates, symlinks, device nodes, nonregular files, decompression limits and malicious/mismatched manifests. Final module ZIP publication is gated on verification of the temporary ZIP bytes.
7. The upstream stale update feed is disabled. `module.prop`, module `update.json` and root `module.prop` have consistent versions; no unaudited ZIP URL is advertised.
8. KernelSU/Magisk packaging safety is exercised through disposable script/ZIP fixtures; installed physical module directories are never modified by the overlay or its tests.
9. Staging, rollback, finalization and uninstall *plans* include audit receipts. An uninstall plan is read-only; it is not a live Android uninstall.
10. Exact-run hosted artifact resolver validates `go-contract` and its required steps, source SHA, run attempt, unique unexpired artifact, `SHA256SUMS`, and on-disk module ZIP identity. It publishes a non-overwriting read-only download receipt. Candidate command generation validates caller-provided exact SHA and identifiers; no live root installation is executed.

## Qualification boundary

The module ZIP is not a device installation. Hosted success, source identity and file hashes are independent checks. No root command runs without separate explicit operator action. R04 source work does **not** imply physical installation, KernelSU/Magisk operational acceptance, or R11 qualification. Full repository Go suite, contracts/final-seal and hosted CI must pass on the applied commit before R04 is eligible for acceptance.

## Command examples after applying overlay

```sh
# Real source and source-bound artifact checksum validation, no installation
python3 scripts/ci/r04_resolve_hosted_module.py --run-id RUN --source-sha COMMIT --output-dir "$HOME/Downloads" --repo-dir .
# Check an offline module artifact
 go run ./cmd/xbsrepo verify-module-zip --zip /absolute/XtremeBS.zip --sha256 EXACT_SHA256
# Generate an operator-reviewed install command (prints only, never executes root)
 go run ./cmd/xbsrepo install-command --zip /absolute/XtremeBS.zip --sha256 EXACT_SHA256 --commit COMMIT --run-id RUN --run-attempt ATTEMPT --artifact NAME --manager kernelsu|magisk
# Offline staged rollback
 go run ./cmd/xbsrepo rollback-stage --stage /absolute/stage
# Offline staged backup finalization, explicit consent
 go run ./cmd/xbsrepo finalize-stage --stage /absolute/stage --ack I_ACCEPT_VERIFIED_STAGE
```

`xbsrepo build-module --install` and `xbsrepo install-module` are refused by the implicit root installer pathway; no app/daemon root side effect is attempted in this overlay.

## R04 HF01 hardening (2026-11-13)

- Fail closed on rollback when the receipt is absent, malformed, or refers to another staged path; explicit recovery also supports interruption after the old stage is renamed, or a corrupted new stage, without deleting failure evidence.
- Require a completed matching upgrade receipt and matching current runtime-manifest hash before finalizing an older backup; refused attempts leave the backup intact.
- Require the local Go ZIP verifier checkout to match the requested CI commit exactly and reject a locally modified/untracked verifier before `go run`. Verify both artifact workflow-run ID and head SHA explicitly; omission is not proof.
- Reject `build-module --install` before compilation or staging. Both implicit installers remain nonoperative.
- Added crash/recovery, fake-receipt, mismatched-checkout, dirty-verifier and wrong-artifact-run regression tests.

These are source/fixture checks, not successful hosted CI or physical installation. Other risks outside this R04 patch include protection against hostile modification of local receipts and independent source-signature verification; the generated install command is only an operator-reviewed candidate.

- Real Go CLI integration tests now cover first-boot idempotency, preservation of user config/essentials/lists, and invalid-configuration refusal on the full checkout. Disposable mocked shell service tests enforce init→validate→daemon order and fail-closed behavior.

- Optional `--latest --source-sha SHA` mode scans bounded recent successful runs at the exact source commit and chooses only an artifact that passes the complete run/job/artifact/ZIP checks; `--run-id` remains the explicit alternative.
