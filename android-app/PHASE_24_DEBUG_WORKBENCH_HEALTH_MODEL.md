# Phase 24: Debug Workbench health model

Phase 24 adds a read-only Debug Workbench that evaluates bounded cached evidence without requesting root or starting background root work.

## Health contract

The pure-Java health policy emits stable check IDs across five domains:

- Root and module
- Storage and history
- Runtime and services
- Notifications
- Observability

Every item carries an independent severity and evidence state. Severity is pass, warning, blocker, or unknown. Evidence is current, stale, or missing. Missing or stale root evidence is never converted into a fabricated current failure.

## Evidence sources

The evaluator uses only existing caches and local Android state:

- cached Root Diagnostics
- cached live/static capability evidence
- Room history and migration state
- cached transaction-journal summary
- notification permission, app, and channel state
- active-run records and in-process service state
- cached runtime state
- cached process visibility evidence

Opening the workbench does not call `su`, run an XBS command, refresh the capability probe, or sample `/proc`. Source workbenches remain responsible for deliberate evidence refreshes.

## Surfaces

- Dedicated private `DebugWorkbenchActivity`
- More and Settings → Diagnostics entry point and summary
- Root Diagnostics and Release Readiness navigation
- support bundle format 18 `[Debug health]` section
- Stable JSON and text serialization for cached health evidence

The artifact keeps Devtool validation disabled and is intended for `apply-overlay --no-validate`.

- Phase 24 r2 makes Debug Health transitively passive: history, active-run, and transaction evidence use cache-only accessors; missing/stale passive evidence is explicit; a device regression protects Room, legacy preferences, and transaction cache from evaluation side effects.
