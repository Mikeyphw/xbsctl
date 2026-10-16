# Phase 13: Root Diagnostics Workbench

Phase 13 adds a dedicated diagnostics activity for root/provider readiness, XBS and XBSctl executable discovery, canonical config/list/backup path probes, notification permission and channel state, foreground-service declarations, and live command/monitor service state.

Guided fixes are intentionally narrow: request root again, explain missing module binaries, open notification settings, or create canonical XBS storage directories and initialize a missing config without overwriting existing files.

The latest cached diagnostics report is included in support bundle format version 2 under `[Diagnostics]`.
