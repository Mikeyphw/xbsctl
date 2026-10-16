# Phase 22: More and Settings information architecture

Baseline: Phase 21 Command Center and Quick Actions result built over Phase 20 commit `beedf20`.

## Scope

Phase 22 replaces the long mixed Settings page with a searchable hub and eight stable destinations:

1. Runtime monitoring
2. Notifications
3. Appearance
4. Tasker
5. Data and retention
6. Configuration tools
7. Diagnostics
8. About

The hub uses tokenized, case-insensitive search across titles, descriptions, and keywords. Every card includes a current state summary gathered from existing bounded caches and preferences without prompting for root.

## Interaction model

Toggles expose an immediate explanatory state beneath the control. Enumerated values use selection dialogs instead of cycle buttons. Notification permission and channel readiness are visible, all four channels open directly in Android settings, and Appearance preserves a live dynamic-color preview. Data and retention owns Room retention, activity cleanup, app metadata refresh, and frontend cache clearing. Configuration tools owns the native editors, Quick Actions, and Command Center. Diagnostics owns root/readiness workbenches and support export.

No technical tool is duplicated across unrelated settings destinations.

## Release gate C

The app advances to `4.2.0` / versionCode `15`. support bundle format 18 adds a bounded non-sensitive Settings section. The release source gate includes the destination model, catalog, searchable hub, and detail host.

## Artifact policy

`validation.mode` is `disabled`, `validation.failure_action` is `keep`, and the overlay is intended for `--no-validate`.
