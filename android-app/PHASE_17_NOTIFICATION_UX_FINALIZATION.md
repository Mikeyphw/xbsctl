# Phase 17: Notification UX finalization

Baseline: Phase 16 commit `1a070b3`.

## Active command model

Active root work is represented by one consolidated foreground-service notification. The app no longer posts a second per-run notification while a command is running or cancelling. A single active command exposes a precise deep link and Cancel action; multiple commands are summarized together.

## Completion policy

Terminal command notifications use a dedicated result channel and a user-selectable policy:

- Off
- Failures, timeouts and cancellations (default)
- All completions

Success notifications are therefore quiet by default while actionable failures remain visible. Completed notifications deep-link to the exact persisted run detail.

## Runtime action safety

Notification actions are checked against the latest cached runtime state before launch. Stale Pause, Resume, Safe mode, Exit safe, and event-stop actions are ignored and the status notification is refreshed. Repeated taps within 1.5 seconds are de-duplicated.

## Channels and diagnostics

Phase 17 separates active command progress from terminal command results and keeps runtime status and important alerts independent. Settings and Root Diagnostics can open each channel directly. support bundle format 18 exports permission, app-level enablement, channel readiness, command completion policy, and the consolidated active-command model.

## Artifact workflow

The artifact sets `validation.mode` to `disabled` and `validation.failure_action` to `keep`. Apply with `--no-validate`.
