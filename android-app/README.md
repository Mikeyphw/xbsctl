# XBS Companion

Native Android GUI frontend for the XtremeBS root module.

Package: `io.github.mikeceead.xbscompanion`  
Version: `4.2.0`

## Native GUI

### Overview

- Runtime availability, running, paused and safe-mode state
- Simultaneous active-event summary and recent activity
- Start, stop, pause, resume, safe-mode, reload and validation controls
- Persistent current-state notification with adaptive controls for zero, one, or several active events
- Optional event-change toasts

### Event manager

XBS events are editable GUI objects rather than command-name fields.

- Create custom events
- Clone existing events
- Rename custom events
- Edit event allowlists manually or with an installed-app picker
- Edit event denylists manually or with an installed-app picker
- Edit per-process nice rules manually or from a live process picker
- Select template inheritance independently for each list
- Validate event names and list content
- Delete custom events and their managed list files
- Protect built-in `template`, `boot`, `charging`, `screen_off`, `low_power`, and `manual` events from deletion or rename
- Allow normal behavior/list edits on built-in events without triggering false rename protection
- Keep Cancel and Validate/save pinned in the event editor footer
- Start and stop standard or custom events through `XBSctl`
- Preserve unknown event properties when editing

### Configuration editor

- Edit header delay, log level, JSON logging, dry-run and notification settings
- Edit runtime/configuration paths
- Edit the default essentials allowlist manually or by selecting installed applications
- Restore the module-provided essentials defaults
- Edit the complete raw JSON when needed
- Validate before saving
- Create timestamped backups
- Write config/list files atomically
- Reload XBS after applying changes


### Live process monitor

- Refreshes every two seconds while visible
- Per-process CPU usage calculated from `/proc` tick deltas
- Resident RAM usage, PID, UID and Linux nice value
- Owning Android app label and package mapping
- Matching XBS process-rule events
- Search plus CPU, RAM and app-name sorting
- Multi-select process-rule picker for event editing

### Root and caching

- Explicit root grant check with KernelSU/KernelSU Next, APatch, Magisk and generic `su` detection
- Separate XBS and XBSctl executable checks
- Cached root capability, configuration, list files, app metadata, rule indexes and process snapshots
- Targeted cache invalidation after configuration/list edits
- Manual cache refresh and clear controls

### Complete command center

A searchable, categorized GUI form is provided for the complete literal `xbs` parser surface and every `XBSctl` command, including global flags, hidden completion support, controller wait/timeout controls and developer operations. Fields are rendered as text boxes, number fields, switches, choices or multiline JSON editors. Destructive operations require confirmation; arbitrary shell input is not exposed.

Published command groups covered:

- Global flags: JSON, quiet and no-interactive
- General: help, status, init, paths, validate
- Configuration: get, set, validate, literal CLI edit, native edit, schema
- Runtime: reload, pause, resume, safe mode
- Events: list, run, stop, native event CRUD
- Controller: help, start, stop, reload, pause, resume, safe, active, status, detail and validate
- Controller transition flags: wait and timeout, with optional event defaulting to manual
- Developer: root schema, schema subcommands, completion—including complete-zsh—and manpage operations

## Schema compatibility and capability negotiation

The module publishes a machine-readable contract through `xbs --json capabilities`. The Android companion caches live evidence for module and CLI versions, Android contract range, active and maximum config versions, advertised command IDs, writable event fields, and SHA-256 schema digests.

- Live contracts are authoritative for Command Center and Quick Actions availability.
- Event editing sends only advertised fields and preserves unsupported existing properties unchanged.
- Older string-based command lists and unknown future fields are parsed safely.
- Missing or unsupported capability output enters a clearly labeled static offline fallback; it is never presented as negotiated live support.
- Root Diagnostics, Release Readiness, and support bundle format 18 expose the same compatibility evidence.

## Android integrations

- Allowlisted root command backend
- Event monitor and bounded local journal
- Persistent status notification using a dedicated non-media status channel and neutral action icons
- Notification event buttons and compact event chooser
- Configurable event-change toasts with deduplication/rate limiting
- Boot restoration
- Command diagnostics with stdout, stderr, JSON result and exit code

## Tasker

XBS Companion exposes:

- **Actions** for the full typed `xbs` and `XBSctl` catalog
- **Events** for event/profile, safe-mode and runtime transitions
- **States** for runtime availability, paused state, safe mode and active events

Runtime transitions are synthesized when XBS changes state without writing a direct journal entry, so Tasker can react to pause, resume, runtime loss and recovery.

Important variables:

```text
%xbs_event
%xbs_event_id
%xbs_timestamp
%xbs_severity
%xbs_message
%xbs_event_name
%xbs_event_action
%xbs_event_active
%xbs_runtime_state
%xbs_running
%xbs_paused
%xbs_safe_mode
%xbs_active_profiles
%xbs_payload_json
```

## Safety model

- No arbitrary root shell console
- Typed/allowlisted command definitions
- Escaped event arguments
- Atomic config writes with backups
- Confirmation for destructive commands
- Known module paths only

## Build from Termux

```bash
python3 gradle-build.py . \
  -t :app:assembleDebug \
  -a /sdcard/Download/aapt2-arm64-v8a.bin \
  -o /sdcard/Download
```

The builder detects Termux and delegates to:

```bash
sudo chrootctl --name androidos exec -- zsh -ilc '<command>'
```

Expected APK:

```text
/sdcard/Download/xbs-companion-debug.apk
```

The builder derives copied artifact names from `rootProject.name`; use `-n NAME` to override the base filename.

The project uses Android Gradle Plugin 9.3.1 and requires Gradle 9.7.1. The included builder rejects incompatible SDKMAN/system Gradle versions and reuses or installs a compatible Gradle distribution.

## Finding event CRUD in the app

Open **Events** from the bottom navigation. The first section is now **Create and manage**, with direct buttons for:

- Create new event
- Open full event manager
- Edit template defaults

Every custom-event card exposes **Edit**, **Clone**, and **Delete event** directly. Built-in event cards expose Edit and Clone, but deletion is intentionally unavailable. CRUD is no longer hidden below the runtime event lists. Root access and a readable `/sdcard/.config/xbs/XtremeBS.json` are required.

## Signed release

Signed APK releases are built from the monorepo with `tools/release/build_android_releases.py`. The release task requires an external keystore and never embeds credentials in source control.

## Event behavior editor

The native event editor exposes every behavior property consumed by the XBS daemon: app handling, GMS handling, process rules, CPU governor/disable controls, Doze mode, low-RAM mode, Wi-Fi handling, charging compatibility and boot quit behavior. List paths are managed internally and remain read-only. App pickers can filter user apps, system apps, or show both.

## Readable results and process diagnostics

Command results are normalized before being shown in cards, toasts and notifications. JSON envelopes, argparse usage output, ANSI sequences and binary-like output are reduced to human-readable summaries; raw stdout/stderr remains available only in diagnostics. The live process monitor uses a root-backed `/proc` sampler with cached app metadata and process snapshots, and reports actionable root or `/proc` errors when sampling is unavailable.

## Phase 00 + 01 foundation

The Android frontend now has a frozen root-only native contract and an AndroidX/Material foundation for the later modernization phases.

- XBS commands remain rooted directly through `su`; no Termux runtime permission, bridge, or command dependency was introduced.
- All activities share an AppCompat base that applies consistent edge-to-edge system-bar insets.
- The application uses a Material 3 dark theme with opt-in Android dynamic colors when enabled in XBS settings.
- Activity, lifecycle, saved-state, RecyclerView, and Room dependencies are present for the state, list, and persistence migrations that follow.
- The existing programmatic View UI and all current Tasker, notification, event, configuration, and process workflows remain intact.
- Permission-denied guidance now points to root authorization and XBS module access instead of Termux.

## Phase 02 locale and min-SDK cleanup

Phase 02 removes the first post-foundation lint-warning cluster without changing app behavior. Internal string normalization now uses `Locale.ROOT`, and min-SDK-26-only foreground-service / notification-channel code no longer carries obsolete Android 25 fallbacks. API gates that are still required above minSdk remain in place.

## Phase 03 storage paths and backup rules

Phase 03 replaces frontend-owned `/sdcard` string literals with Android runtime external-storage paths. The canonical config, list, and backup directories now live in `XbsConfigApi`, and the settings screen reuses those constants for defaults. Android 12+ backup metadata is explicit through `xbs_data_extraction_rules.xml`, while `allowBackup=false` remains in force so root/module configuration is not copied through cloud backup or device transfer.


## Phase 04 UI widget and string-resource cleanup

Phase 04 continues the lint-warning drain without changing the current programmatic View architecture. Toggle controls now use AppCompat `SwitchCompat` so the UI stack is consistent with the AndroidX/AppCompat foundation. The dynamic text compositions previously reported by lint, including navigation labels, summary rows, process samples, read-only paths, and save-state labels, now route through Android string resources with placeholders. The backup metadata also includes `xbs_full_backup_content.xml` for pre-Android-12 compatibility while `allowBackup=false` remains the safety posture.


### Phase 05 warning polish

Phase 05 removes the remaining warning-only cleanup items around Tasker-exported receiver policy, boot action validation, pluralized app/process summaries, launcher icon resources, unused warning-era resources, and the dynamic-color resource lookup suppression.

### Phase 06 final resource warning seal

Phase 06 closes the remaining warning-only resource tail from Phase 05. The unused legacy launcher drawable is removed, and the obsolete `mipmap-anydpi-v26` launcher resource directory is pruned now that XBS has `minSdk` 26. The manifest continues to resolve the unqualified adaptive `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round` resources with monochrome support.


## Phase 07 command policy foundation

Phase 07 adds typed root-command provenance and timeout policy without changing the visible navigation model. App, notification, and Tasker launches now flow through `XbsCommandSpec`, which records the execution source, mutation class, and timeout budget before the command enters the root runner. The current runner is bounded to two workers and treats timeout and cancellation as distinct terminal states.

### Phase 08 foreground command service

Root-backed commands are now owned by `RootCommandService` while they are active. This keeps command execution visible to Android as foreground work, preserves bounded timeouts, and lets notification cancellation record an intermediate `cancelling` state before the final cancelled result is saved.

### Phase 09 active command UI

The Activity tab now treats foreground root commands as first-class runtime work. Running commands appear in an active section with source, mutation class, timeout, output status, details and cancellation controls. Notification-opened run IDs reveal the matching detail dialog automatically.


## Phase 10: Run history filtering

The Activity page now includes command-history filters for active, succeeded, failed, timed-out, cancelled, and output-producing runs. Completed commands can be retried from their stored command envelope, and old finished records can be cleared while active commands remain visible.


## Phase 11: Safe retry guards

Run-history retry now uses a dedicated policy. Active commands and records without persisted commands cannot be retried, destructive replays require confirmation, and retry action IDs are sanitized before they are sent back through the foreground root command service.

## Phase 12: Support bundle export

Settings now exposes copy/share actions for a bounded support bundle. The bundle is plain text and includes app/device metadata, cached root status, cached XBS runtime state, command-history counts, recent run summaries with bounded output tails, and recent event transitions. It uses the clipboard and Android share sheet, so no new storage permission or external-file writer is introduced.

## Phase 13: Root Diagnostics Workbench

Settings now links to a dedicated runtime-readiness screen covering the root provider and version, XBS binaries, config/list/backup path access, notification channels, foreground-service declarations, and active command/monitor service state. The latest report is cached and included in support bundles.

## Phase 14: Configuration transaction journal and rollback

Configuration JSON, managed-list writes, and event-owned list deletions now move through one serialized journaled transaction. The app acquires a root-side lock, snapshots every previous target, records new SHA-256 digests in the immutable plan and old digests in the snapshot journal, stages the complete new set, promotes it, runs `xbs --json validate`, reloads when requested, and verifies the promoted files. Any failure restores and verifies the complete old set. The Configuration screen shows the latest transaction and offers guarded latest-only rollback or retryable interrupted-save recovery. Rollback refuses to overwrite managed files changed after commit. Managed paths are restricted to `XtremeBS.json` and direct files inside the canonical list directory; symlink, traversal, nested-path, size, and entry-count guards are enforced.

## Phase 15: Event editor validation polish

The event editor now validates its draft continuously before root or filesystem work begins. Identity, package entries, allow/deny conflicts, process-rule syntax and nice values, and custom CPU-core actions surface as inline errors or grouped review warnings. Blocking errors disable save. Accepted drafts are captured into an immutable UI-thread snapshot, cloned into a fresh JSON document, and committed through the Phase 14 journal, so background work never reads live widgets or leaves the loaded editor model partially changed.

## Phase 16: Process monitor correctness

The root-backed process sampler now treats `/proc` as evidence rather than a perfect oracle. Aggregate CPU totals exclude guest fields that Linux already includes in user/nice time, and CPU deltas require the same PID plus process start ticks so PID reuse cannot inherit old usage. The scanner reads real NUL-separated cmdlines, preserves `comm` for kernel threads and inaccessible cmdlines, and captures PPID, executable link, SELinux context, and sample time only when observed. Partial or restricted `/proc` visibility is clearly labeled in the process monitor and cached Overview summary, and the bounded diagnostics are exported in support bundle format 18.

## Phase 17: Notification UX finalization

Root commands now use one foreground notification while active, instead of a foreground-service notification plus a duplicate per-run card. A single run links to its exact Activity detail and exposes Cancel; concurrent runs are summarized together. Terminal results move to a separate channel and default to notifying only failures, timeouts, and cancellations, with Off and All completions available in Settings. Runtime actions reject stale state and rapid duplicate taps. Settings and Root Diagnostics can open each notification channel directly, while support bundle format 18 records permission, channel readiness, result policy, and the consolidated command model.

## Phase 18: Release and readiness seal

Settings now exposes a deterministic release-readiness gate with stable blocker, warning, and pass identifiers. It evaluates existing cached root diagnostics, XBS runtime state, process visibility, configuration transaction status, active-command/service consistency, notification readiness, monitor state, build metadata, target SDK, and support-bundle schema without triggering an implicit root prompt. Guided actions route each problem to the responsible workbench. Version 4.2.0 / versionCode 15 adds a Gradle release gate that requires signing readiness, and support bundle format 18 carries the complete readiness report.

## Phase 19: Room history migration

Command history is now stored in a bounded Room database instead of one SharedPreferences JSON array. The migration is idempotent and commits imported records plus its completion marker before removing the old payload. Configuration transaction summaries are also retained locally, while the root-side journal remains authoritative. Settings, release readiness, and support bundle format 18 expose database readiness, migration counts, record counts, and storage size. The existing `RunStore` interface remains stable for command service, notifications, Activity, diagnostics, and deep links.

## Phase 20: Activity workbench

The Activity destination now leads into a dedicated workbench for local command and event evidence. Command records can be searched across metadata and bounded output, filtered by status/source/time/pin state, classified as problems, pinned durably, selected, exported, retried, cancelled, or safely deleted. Runtime events have their own searchable timeline and bounded export. Room schema v2 adds pin persistence without destructive migration, configurable retention protects active and pinned records, notification run links open the exact workbench detail, and support bundle format 18 reports retention and pin evidence.

## Phase 21: Command Center and Quick Actions

The former flat CLI tool list is now a searchable Command Center that treats the published XBS and XBSctl catalog as an allowlist rather than editable shell text. Each action exposes compatibility, risk, expected effect, and timeout before it can run. Forms validate bounded schema fields and continuously render an immutable generated command preview. Destructive actions require a separate explicit confirmation. Direct CLI configuration writes, external editor commands, and terminal-only completion/manpage installation remain discoverable but are disabled in Android with guidance to the transaction-backed native editor or an appropriate terminal environment.

Overview, Events, and Settings now open a compact Quick Actions sheet for validation, reload, pause/resume, safe mode, the manual event, event controls, Root Diagnostics, and the full Command Center. Every executable shortcut routes through the typed root command service and no arbitrary shell field is introduced.

## Phase 22: More and Settings information architecture

The former long Settings page is replaced by a searchable More and Settings hub with eight non-overlapping destinations: Runtime monitoring, Notifications, Appearance, Tasker, Data and retention, Configuration tools, Diagnostics, and About. Each destination card shows an immediate state summary, while detail screens use explicit selection dialogs, contextual explanations, permission readiness, cache controls, direct notification-channel routes, and an immediate dynamic-color preview. Technical tools are owned by exactly one destination rather than repeated across unrelated sections.

Release gate C advances the app to version 4.2.0 / versionCode 15. support bundle format 18 records the non-sensitive settings state needed to reproduce monitor, notification, Tasker, appearance, and retention behavior.

## Phase 24: Debug Workbench health model

Diagnostics now includes a dedicated read-only Debug Workbench. It aggregates cached root, schema compatibility, storage, Room history, transaction, notification, service, runtime, and process evidence into stable health checks. Severity and evidence freshness are modeled separately, so missing or stale evidence is labeled unknown/stale rather than presented as a current failure. Opening or re-evaluating the workbench never requests root, launches XBS commands, refreshes the capability probe, or samples `/proc`; deliberate refreshes remain in their source workbenches. The latest bounded report is cached and exported in support bundle format 18.

- Phase 24 r2 makes Debug Health transitively passive: history, active-run, and transaction evidence use cache-only accessors; missing/stale passive evidence is explicit; a device regression protects Room, legacy preferences, and transaction cache from evaluation side effects.

## Phase 25: Guided fixes and redacted support
- Added private, explicit, allowlisted guided repair flows with risk labels and confirmation.
- Preserved passive Debug Health evaluation; repairs run only from the guided-fix activity.
- Upgraded support bundle format to v12 with deterministic secret/path/package/address/identifier redaction and bounded evidence.

## Phase 26: Authenticated event bridge and adaptive fallback
- Root-owned HMAC-SHA256 envelopes authenticate canonical event/status snapshots.
- Android enforces freshness, replay, duplicate, equivocation, key-rotation, and payload-size policy after root verification.
- Explicit bounded direct polling remains available as a recorded fallback.
- Cached bridge evidence is included in Debug Health, release readiness, diagnostics, Settings, and redacted support bundle format v18.

- Phase 26 adaptive fallback is limited to non-security availability/freshness failures; authentication/replay failures fail closed and preserve security evidence.
Phase 26 r2 uses an explicit availability/freshness fallback allowlist, serializes publication under a private file lock, rejects symlinked or insecure bridge state, and commits the Android replay cursor synchronously before returning authenticated data.
Phase 25 r2 hardens redaction policy v2 for all Authorization schemes, quoted multi-word secrets, full private paths, two-component package names, IPv6 addresses, and modern UUID versions.

Phase 27: Runtime Notification 2.0 adds authenticated transport presentation, state-bound action tokens, foreground-service launch evidence, timeout handling, and support bundle format 18.

- Phase 28 hardens Tasker and all external entry points with signed bounded configurations, sender/host verification, explicit completion delivery, and async receiver discipline.

Phase 29–30: combined manifest/path/stale-action security seal plus accessibility, adaptive layout, and bounded performance evidence.


Phase 29–30 r2 closes the final audit gaps: exact export-set evidence, verified Tasker signer pins, durable cancel-action replay protection, whole-screen 48dp/heading enforcement, expanded content containment, and runtime evidence-backed release readiness.


## Gradle 9.7.1 entry point

Do not run plain `gradle` if your PATH points to Gradle 8.x. AGP 9.3.1 requires Gradle 9.5.0 or newer; this project pins the fleet baseline to 9.7.1. Use Devtool's pinned Gradle when the wrapper is absent:

```bash
export GRADLE="$HOME/.cache/devtool/gradle-build/gradle/gradle-9.7.1/bin/gradle"
"$GRADLE" --version
"$GRADLE" --no-daemon --stacktrace \
  -Pphase31Overlay="$HOME/Downloads/mike_cli_suite_xbs_android_phase31_r3c_rebased_baseline_profile_hotfix_overlay.zip" \
  -Pphase31Baseline="$HOME/Downloads/mike-cli-suite-20260802-192532.tar.gz" \
  phase31FinalReleaseSeal
```
