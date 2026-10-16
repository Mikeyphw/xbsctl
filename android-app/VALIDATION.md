# XBS Companion validation

## Completed checks

- Android manifest and every XML resource parse successfully.
- Every activity added for the GUI is declared in the manifest.
- Native event CRUD is present: create, clone, rename, edit, delete, start and stop.
- Event editing covers allowlist, denylist, process rules and template inheritance.
- Configuration writes use backup plus atomic replacement.
- Custom event start/stop routes through `XBSctl`, not the standard-event-only parser.
- The typed catalog covers all 27 supported literal `xbs` forms, including help and the root schema form, including help and the root schema form.
- The typed catalog covers all 11 `XBSctl` forms, including help, reload, pause, resume, safe, optional manual-event defaults, wait and timeout.
- Tasker Action uses the same 38 runnable typed commands as the in-app command center.
- Global `xbs` flags (`--json`, `--quiet`, `--no-interactive`) are selectable.
- All production Java files pass a compiler parser pass; the pure Java CLI catalog compiles and representative command-generation checks pass.
- The Python Gradle builder passes Python syntax validation and enforces Gradle 9.7.1 / JDK 21 compatibility.
- No Sortify or WallGlide application code is included.

## Build limitation in this environment

A complete Android APK was not produced here because this container does not include an Android SDK platform or the ARM64 AAPT2 binary used by the target Ubuntu chroot. Build the project with the included builder on the configured ARM64 environment.

## 3.0.1 event CRUD discoverability correction

- The Events page now starts with visible Create new event, Open full event manager, and Edit template defaults controls.
- Each custom-event card directly exposes Edit, Clone, and Delete; protected built-in event cards expose Edit and Clone only.
- The Overview page includes Create event and Open event manager shortcuts.
- Configuration-load failures now explain that root/config access is required instead of silently hiding CRUD.
- Java parser pass found no syntax-level errors in production sources; unresolved diagnostics were Android SDK symbols only in this container.

## 3.2.0 native-frontend-only correction

- Removed the legacy browser WebUI, CGI backend, WebUI command namespace, and old Tasker WebView bundle.
- Native Android event/configuration management is now the only supported GUI.

## 3.1.0 full CLI and notification correction

- Added literal `xbs config edit` fields for `--editor` and `--print-path`.
- Added the hidden `completion complete-zsh` endpoint with `--words` and `--index`.
- Added the root `xbs schema` form and every schema subcommand.
- Added complete XBSctl command coverage and transition options.
- Replaced the curated Tasker action list with the complete typed CLI catalog.
- Migrates previously saved curated Tasker actions when reopened.
- Replaced Android built-in media action icons with app-owned neutral vector icons.
- Moved foreground status to `xbs_runtime_status_v2`, deletes the old status channel, uses `CATEGORY_STATUS`, and contains no media style/session/service type.
- Full Android compilation could not run in this container because Android command-line tools could not be downloaded due unavailable DNS.


## 3.4.0 root, lists, processes and multi-event correction

- Added an explicit cached root capability check and root-manager detection.
- Added a native default-essentials editor with manual and installed-app selection modes.
- Added installed-app pickers for event allowlists and denylists.
- Added a live process monitor and process-rule picker with CPU, RSS, PID, UID, nice value and owning app/package.
- Added layered caching for root, configuration, list files, app metadata, process-rule indexes and live process samples.
- Protected template, boot, charging, screen_off, low_power and manual events from deletion and rename at both UI and config-save layers.
- Updated snapshot parsing, notifications, Tasker variables and transition journaling for multiple simultaneously active events.
- Java parser validation reports no syntax-level errors in production sources.

## 3.5.2 reliability pass

- Built-in event saves now pin the immutable event ID and never infer a rename from the disabled name field.
- The event editor keeps Cancel and Validate/save in a fixed bottom action bar while the form scrolls.
- Added typed editing for every event behavior property used by `XtremeBSd`; list paths remain managed/read-only.
- Added user/system/both filters to installed-app selection.
- Reworked the live process sampler to avoid per-process subprocess overhead, check root explicitly and report `/proc` restrictions.
- Added centralized human-readable command-result formatting for app UI and notifications.

## Phase 00 + 01 baseline and AndroidX foundation

- Added permanent root-only and native-feature source contracts.
- Enabled AndroidX with Jetifier disabled and migrated all activities through a shared AppCompat base.
- Added Material 3 dark theming and opt-in dynamic color application.
- Centralized edge-to-edge system-bar inset handling.
- Added explicit dependencies for later RecyclerView, lifecycle, saved-state, and Room migrations.
- Confirmed that no Termux runtime permission, intent, bridge class, or recovery wording is present.
- Devtool validation must complete every required phase generated by the selected `xbs_android` target before finalization.


## AGP 9.3 lint policy hotfix

Phase 00+01 r4 keeps lint errors at the Android policy boundary instead of suppressing the whole lint task. It uses AndroidX receiver flags, moves the navigation-bar API-27 attribute into a qualified resource file, and keeps `QUERY_ALL_PACKAGES` with an explicit manifest-level lint suppression because the root process monitor and app/list rule builders require visibility into arbitrary installed packages.


## Devtool commit handoff hotfix

Phase 00+01 r5 adds repository ignore rules for `.devtool/artifacts.json`, `.devtool/artifacts.d/`, and `.devtool/build-artifacts/`. These paths are local Devtool state, not XBS source payload. Keeping them ignored prevents the automatic post-validation commit from staging a non-existent artifact-history file on repositories that have not opted into committed Devtool history.

## Phase 02 locale and min-SDK cleanup

- All internal lowercase/uppercase normalization paths touched by lint now use `Locale.ROOT`.
- Foreground-service startup and notification channel construction no longer carry Android 25 fallback branches because `minSdk` is 26.
- Runtime guards that still matter above API 26 are retained, including API 31 foreground-service behavior and API 33 notification permission checks.
- Focused validation can run with repeatable Devtool tasks: `--task :app:testDebugUnitTest --task :app:lintDebug`.

## Phase 03 storage paths and backup rules

- XBS Android-owned config/list path defaults no longer contain hardcoded `/sdcard` string literals in Java sources.
- `XbsConfigApi` derives the external-storage root through Android's runtime environment API and exposes canonical config, list, and backup directory constants.
- `AndroidManifest.xml` declares `android:dataExtractionRules="@xml/xbs_data_extraction_rules"` while preserving `android:allowBackup="false"`.
- `xbs_data_extraction_rules.xml` excludes root and external domains for cloud backup and device transfer.
- Focused validation can run with repeatable Devtool tasks: `--task :app:testDebugUnitTest --task :app:lintDebug`.


## Phase 04 UI widget and string-resource cleanup

- Programmatic toggle controls in the reported activities use `androidx.appcompat.widget.SwitchCompat`; platform `android.widget.Switch` references are absent from those sources.
- Reported dynamic `setText` compositions now use string resources with placeholders for navigation labels, status lines, app-picker summaries, process-monitor summaries, read-only paths, and save-state labels.
- `AndroidManifest.xml` declares both `android:dataExtractionRules` and `android:fullBackupContent`; both XML rule files exclude root and external domains.
- Focused validation can run with repeatable Devtool tasks: `--task :app:testDebugUnitTest --task :app:lintDebug`.


## Phase 05 warning polish

Focused validation for Phase 05 uses `:app:testDebugUnitTest` and `:app:lintDebug`. Expected result: tests pass, lint reports no errors, and the prior AppPalette, plural, BootReceiver, exported Tasker receiver, obsolete mipmap-v26, unused resource, Theme.MikeControl, and monochrome launcher warnings are removed or explicitly justified.


## Phase 06 final resource warning seal

Focused validation for Phase 06 should include resource linking because it deletes launcher-era resources:

```bash
devtool --yes \
  --copy \
  --target xbs_android \
  --host \
  -r "$HOME/Code/mike-cli-suite" \
  apply-overlay "$HOME/Downloads/mike_cli_suite_xbs_android_phase06_final_resource_warning_seal_overlay.zip" \
  --validate \
  --task :app:processDebugResources \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug
```

Expected result: resource linking succeeds, unit tests pass, lint reports no errors, and the previous `mipmap-anydpi-v26` / `ic_launcher_legacy` warning tail is gone.


## Phase 07 validation

Phase 07 touches Java command execution policy only. The focused Android validation set is:

```bash
--task :app:testDebugUnitTest --task :app:lintDebug
```

Expected result after apply: unit tests pass and Android lint reports zero errors.

## Phase 08 validation

Phase 08 touches Java command-service execution and manifest service wiring. The focused Android validation set is:

```bash
--task :app:testDebugUnitTest --task :app:lintDebug
```

Expected result after apply: unit tests pass, Android lint reports zero errors and zero warnings, and command notifications can cancel an active run through the context-aware cancellation path.

## Phase 09 validation

Phase 09 touches Java UI/helper/test code only. The focused Android validation set is:

```bash
--task :app:testDebugUnitTest --task :app:lintDebug
```

Expected result after apply: unit tests pass, Android lint reports zero errors and zero warnings, and command notifications can open the Activity tab with a matching run detail dialog.


## Phase 10 validation

Run Java-only focused validation with `:app:testDebugUnitTest` and `:app:lintDebug`. Resource validation is not required for this phase.


## Phase 11 validation

Safe retry guards are Java-only. Validate with `:app:testDebugUnitTest` and `:app:lintDebug`; resource processing is not required for this phase.

## Phase 12 validation

Support bundle export is Java-only. Validate with `:app:testDebugUnitTest` and `:app:lintDebug`; resource processing is not required for this phase. Expected result: unit tests pass, Android lint reports zero errors and zero warnings, and Settings exposes copy/share bundle actions without requesting storage permissions.

## Phase 13 validation

Focused tasks: `:app:testDebugUnitTest` and `:app:lintDebug`. Phase 13 adds Java sources, one manifest activity declaration, unit contracts, and repository source contracts. It does not add layout, drawable, or values resources.

## Phase 14 validation

Phase 14 is Java-only and does not add or change Android resources. Validate with:

```bash
--task :app:testDebugUnitTest --task :app:lintDebug
```

Expected result: at least 40 unit tests pass, lint reports zero errors and zero warnings, config/list saves use the serialized journaled transaction engine, managed event-file deletion is part of the same transaction, old/new SHA-256 digests are recorded, stale rollback is refused, interrupted transactions remain recoverable, and the Phase 13 `FOREGROUND_SERVICE` `InlinedApi` warnings are absent.

Artifact preflight also fault-injects successful commit/rollback, post-commit external modification, runtime validation failure, live/stale lock handling, and process death during promotion. Every scenario must leave either the exact old set or the exact committed set, never a hybrid after recovery.

## Phase 15 validation

Phase 15 changes Java editor, validation, and test sources only. Validate with:

```bash
--task :app:testDebugUnitTest --task :app:lintDebug
```

Expected result: at least 48 unit tests pass, lint reports zero errors and zero warnings, malformed event names/packages/process rules/core lists are blocked inline, warnings are reviewed before save, and accepted drafts still commit through the Phase 14 transaction journal. Android resource processing is not required.

## Phase 16 validation

Phase 16 changes Java process-sampling, monitor, support-bundle, documentation, and test sources only. It does not add or modify Android resources.

The artifact preflight covers:

- aggregate `/proc/stat` parsing that excludes `guest` and `guest_nice`;
- short Android kernel CPU-line variants;
- spaces in `comm` and NUL-separated cmdline capture;
- stable `pid:startTicks` identity and PID-reuse rejection;
- multi-core CPU scaling and counter-regression rejection;
- inaccessible or malformed `/proc` entries;
- PPID, executable, SELinux context, sample timestamp, and observation flags;
- partial-visibility UI and support bundle format 18 evidence;
- generated root shell syntax and a real host `/proc` parse.

Per the current XBS Android workflow, the artifact manifest sets `validation.mode` to `disabled` with `validation.failure_action` set to `keep`. Apply with `--no-validate`. A later explicit repository validation may use `:app:testDebugUnitTest` and `:app:lintDebug`; resource processing is not required.

## Phase 17 validation

Phase 17 changes Java notification policy, foreground-service presentation, settings/diagnostics UI, support-bundle, documentation, and focused test sources. It does not add or modify Android resources or manifest declarations.

Artifact preflight covers:

- one consolidated notification for active root commands;
- no per-run notification while status is running or cancelling;
- default failures/timeouts/cancellations completion policy plus Off and All modes;
- exact run-detail deep links and single-run cancellation;
- stale runtime-action rejection and 1.5-second duplicate-tap suppression;
- separate progress, result, runtime, and important-alert channels;
- channel-specific settings/repair intents;
- namespaced dynamic notification IDs;
- support bundle format 18 notification readiness and policy evidence;
- compatibility with the standing Phase 8, 13, 14, 15, and 16 contracts.

The artifact manifest sets `validation.mode` to `disabled` with `validation.failure_action` set to `keep`. Apply with `--no-validate`. A later explicit repository validation may use `:app:testDebugUnitTest` and `:app:lintDebug`; resource processing is not required.

## Phase 18 validation

Phase 18 adds the pure-Java release-readiness policy/report, a code-only readiness activity, Android cache evidence collection, one manifest activity declaration, Gradle release metadata and signing gates, support bundle format 18, and focused tests. It does not add Android resources.

Artifact preflight covers:

- blocker, warning, and pass state transitions;
- stale diagnostics as a blocker rather than a silent pass;
- unsafe transaction and orphaned-command states;
- debug-build attention without blocking operational readiness;
- runtime and process freshness windows;
- partial `/proc` visibility;
- no-prompt collection from existing bounded caches;
- guided navigation and manifest registration;
- version 4.2.0 / versionCode 15 and targetSdk 36;
- `verifyReleaseReadiness` depending on release signing;
- support bundle format 18 itemized readiness evidence.

The artifact manifest sets `validation.mode` to `disabled` with `validation.failure_action` set to `keep`. Apply with `--no-validate`. A later explicit repository validation may use `:app:testDebugUnitTest` and `:app:lintDebug`. A distributable build must run `verifyReleaseReadiness` through `assembleRelease` or `bundleRelease` with all signing variables available.

## Phase 19 validation

Phase 19 adds Room entities, DAO/database/store infrastructure, a crash-safe one-time SharedPreferences migration, transaction-summary retention, readiness/support evidence, and focused tests. It does not add Android resources or manifest declarations.

Artifact preflight covers:

- Room database version 1 with run, transaction, and metadata tables;
- primary-key run upserts and bounded retention;
- no destructive migration fallback;
- one transaction for imported rows plus the migration marker;
- removal of the legacy payload only after the Room transaction returns;
- malformed-row skip accounting;
- persistent configuration-transaction summaries capped at 32;
- support bundle format 18 `[Local history]` evidence;
- release blockers for an unavailable database or incomplete migration;
- source compatibility with command execution, notifications, Activity, diagnostics, and deep links.

The artifact manifest sets `validation.mode` to `disabled` with `validation.failure_action` set to `keep`. Apply with `--no-validate`. A later explicit repository validation may use `:app:testDebugUnitTest` and `:app:lintDebug`; Android resource processing is not required.

## Phase 20 validation

Phase 20 adds a code-only Activity workbench, Room schema 1→2 migration, pin-aware retention, notification deep-link changes, support bundle format 18, and focused Java/repository tests. It does not add Android resources.

Artifact preflight covers:

- non-destructive Room migration adding `pinned` plus its index;
- command search across metadata and bounded stdout/stderr/error evidence;
- status, problem, source, time-window, and pinned-only filters;
- searchable important-event evidence;
- active-first and pinned-second retention;
- pin-safe Clear finished and filtered bulk deletion;
- explicit selection and bounded export;
- configurable retention at 30, 60, 120, or 240 records;
- exact notification deep links into the workbench;
- support bundle format 18 pin and retention evidence;
- compatibility with standing Phase 13–19 contracts.

The artifact manifest sets `validation.mode` to `disabled` with `validation.failure_action` set to `keep`. Apply with `--no-validate`. A later explicit repository validation may use `:app:testDebugUnitTest` and `:app:lintDebug`; Android resource processing is not required.

## Phase 21 validation

Phase 21 replaces the Command Center activity implementation, adds a pure policy model and Quick Actions bottom sheet, strengthens typed catalog execution, and adds focused tests. It does not add Android resources or manifest declarations.

Artifact preflight covers:

- all published XBS/XBSctl catalog entries receiving risk, expected-effect, timeout, and availability metadata;
- immutable generated command previews with no editable shell field;
- exact catalog-instance allowlisting through `XbsCommandSpec.fromCatalog`;
- forged catalog rejection and shell-quoted user values;
- bounded integer, word-count, line, and payload validation;
- Android-disabled direct config writes, external editor commands, completion installation, and manpage installation;
- explicit confirmation for destructive event-stop actions;
- fresh/missing/stale executable compatibility evidence;
- typed Quick Actions for validate, reload, pause/resume, safe mode, diagnostics, Command Center, and manual-event control;
- compatibility with the standing Phase 13–20 contracts.

The artifact manifest sets `validation.mode` to `disabled` with `validation.failure_action` set to `keep`. Apply with `--no-validate`. A later explicit repository validation may use `:app:testDebugUnitTest` and `:app:lintDebug`; Android resource processing is not required.

## Phase 22 validation

Phase 22 replaces the long Settings page with a searchable information architecture and one detail host for eight non-overlapping destinations. Artifact checks cover:

- all eight stable destination IDs, titles, descriptions, and search keywords;
- tokenized case-insensitive search with no duplicate destination IDs;
- immediate hub summaries for monitor state, notification readiness, dynamic colors, Tasker, Room retention, diagnostics, and version metadata;
- selection dialogs for polling interval, command-result alerts, toast feedback, and history retention;
- immediate toggle summaries for monitoring, boot restart, detailed status, dynamic colors, and Tasker emission;
- direct routes to all four notification channels;
- cache refresh and clearing controls only under Data and retention;
- configuration editors and typed command surfaces only under Configuration tools;
- diagnostics and support-bundle actions only under Diagnostics;
- version 4.2.0 / versionCode 15 and support bundle format 18;
- compatibility with standing Phase 13–21 contracts.

The artifact manifest sets `validation.mode` to `disabled` and `validation.failure_action` to `keep`. Apply with `--no-validate`.

## Phase 23 validation

Phase 23 adds the module/CLI compatibility contract and Android capability negotiation without new Android resources or manifest declarations. Artifact preflight covers:

- deterministic `xbs --json capabilities` output with module, CLI, Android-contract, config, command, event-field, and SHA-256 evidence;
- exact command IDs shared with the Android catalog, excluding native-only destinations;
- older string command fixtures and unknown newer fields;
- explicit static fallback when live output is unavailable;
- live omission blocking Command Center and Quick Actions execution;
- event-editor field gating and preservation of unsupported existing properties;
- compatibility evidence in Root Diagnostics, Release Readiness, Settings, and support bundle format 18;
- support bundle and historical source-contract reconciliation;
- no arbitrary-shell or Termux command surface.

The artifact manifest sets `validation.mode` to `disabled` and `validation.failure_action` to `keep`. Apply with `--no-validate`.

## Phase 24 validation

Phase 24 adds the Debug Workbench health model without Android resource changes. Artifact preflight covers:

- stable blocker, warning, pass, and unknown severities;
- independent current, stale, and missing evidence states;
- bounded JSON and support-text serialization with stable check IDs;
- cached-only evaluation with no root request, command execution, compatibility probe, or `/proc` refresh;
- root, binary, compatibility, storage, history, transaction, notification, command-service, monitor, runtime, and process checks;
- stale root evidence remaining unknown rather than becoming a fabricated blocker;
- Room and active-run consistency from the real repository APIs;
- private manifest registration and navigation from Settings, Root Diagnostics, and Release Readiness;
- support bundle format 18 with a `[Debug health]` section;
- focused strict Java compilation, policy runtime harness, full XBS source contracts, source-hash replay, and extracted-artifact replay.

The artifact manifest sets `validation.mode` to `disabled` and `validation.failure_action` to `keep`. Apply with `--no-validate`.

Phase 24 r2 sandbox source-contract result: 156 passed, 0 failed across the XBS source contracts. Android SDK/Gradle validation was not run in the sandbox and remains disabled in the artifact by user request.

- Phase 24 r2 makes Debug Health transitively passive: history, active-run, and transaction evidence use cache-only accessors; missing/stale passive evidence is explicit; a device regression protects Room, legacy preferences, and transaction cache from evaluation side effects.

## Phase 25: Guided fixes and redacted support
- Added private, explicit, allowlisted guided repair flows with risk labels and confirmation.
- Preserved passive Debug Health evaluation; repairs run only from the guided-fix activity.
- Upgraded support bundle format to v12 with deterministic secret/path/package/address/identifier redaction and bounded evidence.

## Phase 26: Authenticated event bridge and adaptive fallback
- Root-owned HMAC-SHA256 envelopes authenticate canonical event/status snapshots.
- Android enforces freshness, replay, duplicate, equivocation, key-rotation, and payload-size policy after root verification.
- Explicit bounded direct polling remains available as a recorded fallback.
- Cached bridge evidence is included in Debug Health, release readiness, diagnostics, Settings, and redacted support bundle format 18.

- Phase 26 adaptive fallback is limited to non-security availability/freshness failures; authentication/replay failures fail closed and preserve security evidence.
Phase 26 r2 uses an explicit availability/freshness fallback allowlist, serializes publication under a private file lock, rejects symlinked or insecure bridge state, and commits the Android replay cursor synchronously before returning authenticated data.
Phase 25 r2 hardens redaction policy v2 for all Authorization schemes, quoted multi-word secrets, full private paths, two-component package names, IPv6 addresses, and modern UUID versions.

Phase 27: Runtime Notification 2.0 adds authenticated transport presentation, state-bound action tokens, foreground-service launch evidence, timeout handling, and support bundle format 18.

## Phase 28
Verify Tasker protocol-v2 signatures, package/certificate pinning, sender checks, bounded flat CLI fields, destructive opt-in, `goAsync()` completion, completion-intent rejection, private boot receiver, support format 18, and no arbitrary shell entry.

Phase 29–30: combined manifest/path/stale-action security seal plus accessibility, adaptive layout, and bounded performance evidence.


Phase 29–30 r2 closes the final audit gaps: exact export-set evidence, verified Tasker signer pins, durable cancel-action replay protection, whole-screen 48dp/heading enforcement, expanded content containment, and runtime evidence-backed release readiness.
