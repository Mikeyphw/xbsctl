# XBS Python/shell → Go migration ledger

This ledger tracks the staged replacement of the XBS Python CLI and shell-backed
module runtime with a Go-native backend. The Android app remains Java/Kotlin-side
and must continue to use the published command/capability contract until a phase
explicitly changes that contract.

## Non-negotiable rules

1. Production XBS behavior remains on the existing Python/shell runtime until the
   corresponding Go slice passes differential or frozen-contract parity tests.
2. `xbs --json capabilities` remains the Android compatibility source of truth.
3. Existing config paths, config schema version 2, event names, and Android
   command IDs remain stable unless a later migration phase documents a contract
   transition.
4. A phase is complete only when the previous Python/shell behavior is represented
   by Go tests, Android tests, shell-contract tests, or an explicitly retired
   frozen contract.

## XGO00 — Freeze current contracts — COMPLETE

Delivered in `apps/xbs/migration/contracts/v1/` and
`apps/xbs/migration/golden/v1/python_cli/`:

- Python CLI command catalog fixture with 17 production command nodes.
- Compatibility fixture with 39 advertised Android/module command rows.
- Config schema fixture for schema version 2.
- Event bridge envelope contract for authenticated HMAC format version 1.
- XBSctl shell controller command fixture with 11 controller commands.
- Android command catalog fixture extracted from `XbsCliCatalog.java`.
- Representative Python machine-output snapshots with volatile paths normalized.
- `apps/xbs/tools/xgo00_check_contracts.py` to detect fixture/source drift.

## XGO01 — Go skeleton + Android ARM64 build spike — COMPLETE

Delivered in `apps/xbs/cmd/xbs` and `apps/xbs/internal/*`:

- Go module scaffold for the future native XBS CLI.
- Version and migration-phase identity.
- Frozen-contract validator and summary command.
- `xbs contracts check` and `xbs contracts summary` as safe scaffold commands.
- Production command refusal for all non-contract commands until parity phases land.
- Go unit tests proving contract validation and production-command refusal.
- Android/arm64 CGO-free build proof for the scaffold.

The module launcher and current `/system/bin/xbs` Python/shell production path are
intentionally unchanged in XGO01.

## XGO02 — CLI parser and machine-output parity — COMPLETE

Delivered in `apps/xbs/internal/cli`, `apps/xbs/internal/machine`, and
`apps/xbs/internal/schema`:

- Go parser coverage for the frozen 17 `xbs` production command nodes.
- Stable machine-output envelope with schema version 2.0.0.
- Implemented safe/config-only commands: `status`, `init`, `paths`, `validate`,
  `config get`, `config set`, `config validate`, `config schema`, `event list`,
  and `schema` helpers.
- Runtime-control, capabilities, event-bridge, completion, and manpage commands
  are recognized but continue to fail closed until their dedicated parity phases.
- Parser/schema tests verify the Go command set still matches the frozen contract.

## XGO03 — Config model, validation, and atomic writes — COMPLETE

Delivered in `apps/xbs/internal/config`:

- Go-native config defaults for schema version 2 and the existing XBS path layout.
- JSON load/merge/save behavior for `XtremeBS.json`.
- Validation parity for header fields, event object shape, boolean-like fields,
  enumerated action fields, and core/proc string fields.
- Config key-path get/set helpers used by the Go CLI.
- Atomic config/allowlist writes with fsync and symlink rejection.
- Corrupt JSON now fails closed instead of silently treating invalid config as
  empty defaults.

The module launcher, `XBSctl`, `XtremeBSd`, Python CLI, and Android runtime
resolver remain unchanged; the Go binary is still not the production module entry
point.

## XGO04 — Compatibility contract parity — COMPLETE

Delivered in `apps/xbs/internal/compat`:

- Go-native `xbs --json capabilities` payload generation.
- The frozen 39-row Android/module command catalog is reproduced in Go.
- Module identity is read from `module.prop` with bounded property parsing and safe-id checks.
- Config schema event fields are extracted from `schema.json` with bounded parsing.
- Contract, schema, command, and capability digests match the frozen Python fixture.
- `capabilities` is now implemented by the Go binary instead of failing closed.

The production module launcher and Android resolver are still unchanged; this phase
only proves the Go binary can publish the existing compatibility surface.

## XGO05 — Authenticated event bridge — COMPLETE

Delivered in `apps/xbs/internal/eventbridge` and wired into `cmd/xbs`:

- HMAC-SHA256 event bridge key creation, publish, and verify paths in Go.
- Canonical JSON signing, key-id generation, bounded source/envelope reads, and
  sequence persistence.
- Private parent/file checks for 0700/0600 bridge assets.
- Symlink rejection, trusted-source owner/mode checks, atomic fsync writes, and
  exclusive publication locking.
- `xbs --json event-bridge`, `xbs --json event-bridge publish`, and
  `xbs --json event-bridge verify` now run without Python.

`XtremeBSd` still calls the legacy Python publisher until XGO06 switches the
module launcher/runtime integration to the Go binary.

## XGO06 — Go `xbs` launcher cutover with legacy daemon retained — COMPLETE

Delivered in `apps/xbs/system/bin/xbs` and the mirrored module payload:

- `/system/bin/xbs` now resolves the staged or active module root and execs the
  bundled Go runtime at `runtime/arm64-v8a/xbs`.
- The launcher checks that the runtime exists, is not a symlink, and has
  executable mode before dispatching.
- Active and staged Magisk/KernelSU module roots are supported:
  `/data/adb/modules`, `/data/adb/modules_update`, `/data/adb/ksu/modules`, and
  `/data/adb/ksu/modules_update`.
- `XtremeBSd` remains the legacy bash daemon, but its authenticated event bridge
  publication now calls the Go `xbs event-bridge publish` implementation instead
  of `python -m xbs_cli.event_bridge`.

`XBSctl`, `XtremeBSd`, bundled `bash`, bundled `bc`, and `yq` are intentionally
retained until XGO08-XGO10 replace the shell controller/daemon and remove the
remaining shell parser dependencies.

## XGO07 — Go-native module packaging — COMPLETE

Delivered in `apps/xbs/cmd/xbsrepo` and `apps/xbs/internal/module`:

- `xbsrepo build-runtime` builds the Android/ARM64 Go `xbs` runtime.
- `xbsrepo build-module` stages the module tree, injects the Go runtime, writes a
  runtime manifest, validates executable modes, and creates a deterministic ZIP.
- `xbsrepo install-module` supports KernelSU (`ksud module install` and
  `/data/adb/ksud module install`) and Magisk (`magisk --install-module`).
- Module ZIP creation sorts entries, fixes timestamps, preserves executable mode
  for launchers/runtime, rejects symlinks, and avoids ZIP self-inclusion.
- `xbsrepo validate` and `xbsrepo final-seal` prove the XGO07 staging/runtime
  contract without requiring the Android app resolver to change yet.

The production Python package remains present in the source tree only for later
migration comparison and rollback; the module `xbs` entrypoint no longer requires
Python for `capabilities`, config-safe commands, or event-bridge publishing.
### XGO08/XGO09 — Go controller and first Go daemon runtime — IMPLEMENTED

Delivered in `xbs_xgo08_09_controller_daemon_devtool_v1`:

- Added Go `cmd/XBSctl` and `internal/control` for status, active/detail, validation, and control-file queuing.
- Added Go `cmd/XtremeBSd` and `internal/daemon` with a conservative status/event bridge runtime loop.
- Replaced module `system/bin/XBSctl` and `system/bin/XtremeBSd` with safe launchers to bundled Go runtimes.
- Retained legacy shell scripts under `system/bin/legacy/` and `module/system/bin/legacy/` for rollback/audit.
- Extended `xbsrepo`/module staging to build and validate `xbs`, `XBSctl`, and `XtremeBSd` Android/ARM64 runtimes.

The daemon implementation is intentionally conservative: it processes explicit controller commands and publishes status/event bridge data, but it does not yet remove bundled `bash`, `bc`, or `yq`; that purge remains XGO10.



## XGO10 — Runtime dependency purge — COMPLETE

Delivered in `apps/xbs/internal/module` and the mirrored module payload:

- The staged module ZIP no longer contains bundled `bin/yq`, `system/bin/bash`, `system/bin/bc`, or `yq-update.sh`.
- `service.sh` starts the Go-native `system/bin/XtremeBSd` launcher directly instead of invoking bundled bash.
- `customize.sh` no longer downloads or permissions yq/bash/bc runtime dependencies.
- `xbsrepo` validation fails if the staged module contains those legacy runtime dependencies.

Legacy shell scripts remain in source-only `system/bin/legacy/` copies for audit/rollback notes, but they are not part of the Go-native packaged runtime contract.

## XGO11 — Android Go-native runtime resolver — COMPLETE

Delivered in the Android companion resolver/compatibility code:

- Android root-command resolution no longer exposes or searches for `yq` as a runtime dependency.
- Android can resolve all three Go-native module entrypoints: `xbs`, `XBSctl`, and `XtremeBSd`.
- `xbs --json capabilities` now publishes a `runtime` contract with `kind=go-native` and `python_required`, `yq_required`, `bash_required`, and `bc_required` set to false.
- Android compatibility parsing caches and reports the runtime kind and dependency-free runtime booleans.
- Live compatibility is rejected if a module reports Python/yq/bash/bc as required.

## XGO12 — Final Python/runtime purge — COMPLETE

Delivered in the final Go-native source tree:

- Removed the retired Python XBS package from the production app path: `src/xbs_cli/`, `pyproject.toml`, and `install.py`.
- Removed the old packaged runtime dependencies from source/module mirrors: bundled `yq`, bundled `bash`, bundled `bc`, and `yq-update.sh`.
- Removed rollback-only legacy shell copies now that `xbs`, `XBSctl`, and `XtremeBSd` all have Go entrypoints.
- Reworked the XGO00 contract checker so it compares frozen fixtures to the Go compatibility table instead of reading the retired Python `compatibility.py`.
- The production module/build path is now Go-native and does not require Python, mike-cli-kit, Rich, yq, bundled bash, or bundled bc.

Migration fixtures and the XGO00 checker remain as audit tooling; they are not part of the production module runtime.

## XGO13 — Final Go-native release seal — COMPLETE

Delivered in `apps/xbs/internal/module` and `cmd/xbsrepo`:

- `xbsrepo final-seal` now runs the full Go test suite before packaging checks.
- The seal fails if legacy Python package paths, `yq`, bundled `bash`, bundled `bc`, yq updater files, or rollback shell copies remain in production paths.
- The seal fails if the Go CLI still contains fail-closed `notImplemented` production stubs.
- The seal builds all three Android/ARM64 runtimes, stages the module, validates executable modes and runtime manifests, and verifies deterministic module ZIP output by hashing two independently generated ZIPs.
- The Go `xbs` CLI now implements the previously fail-closed production command paths by delegating runtime/event control to the Go `XBSctl` controller and by providing Go-native completion/manpage/config-edit outputs.

At this point the XBS module runtime is Go-native across `xbs`, `XBSctl`, and `XtremeBSd`; Android compatibility reports `runtime.kind = go-native` and all legacy dependency booleans as false.

### XGO12a — Devtool target handoff

Devtool target `xbs` now validates through the Go command target instead of the legacy Python package target. The legacy Python package files are kept for this transition overlay only so the already-loaded Python target in the apply process can finish its rollback-safe validation. XGO12/XGO13 performs the production Python purge after this handoff is committed.


### XGO13b — Repository handoff and Git hygiene hotfix

The XGO13 code/runtime seal was complete inside `apps/xbs`, but the monorepo
handoff still had repository-level references to the retired Python XBS package.
This hotfix closes those full-roadmap gaps:

- repository-wide Devtool suite no longer installs `apps/xbs` as a Python editable package;
- repository-wide Python compileall no longer treats `apps/xbs` as a Python package;
- the XBS target builds Go binaries with `-buildvcs=false -trimpath` for copied/root-owned worktrees;
- generated `apps/xbs/.tmp/` validation artifacts are ignored;
- root README module commands use `go run ./cmd/xbsrepo` instead of retired `apps/xbs/install.py`;
- Python CLI artifact generation no longer imports `xbs_cli.cli`;
- `xbsrepo final-seal` now checks those repository handoff invariants and cleans final-seal scratch outputs.
