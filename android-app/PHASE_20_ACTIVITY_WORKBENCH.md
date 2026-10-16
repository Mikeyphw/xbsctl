# Phase 20: Activity Workbench

Baseline: Phase 19 Room history migration overlay on Phase 18 commit `b776fe3`.

Phase 20 turns the former inline Activity feed into a dedicated evidence workbench while preserving the compact Activity tab summary. Command notifications and foreground-command status now deep-link into the dedicated screen and reveal the exact run.

## Workbench behavior

- Search command title, action, command, status, source, mutation class, message, stdout, stderr, error, and run ID.
- Search the bounded runtime event timeline by event identity, type, name, state, severity, and message.
- Filter command records by status, source, time window, and pinned-only state.
- Classify failure, timeout, cancellation, stderr, and explicit error evidence as problems.
- Pin important command records durably in Room.
- Select finished records, export selected or visible evidence, and delete selected local records.
- Clear the current filter without deleting pinned or active records.
- Configure command-history retention at 30, 60, 120, or 240 records.

## Room migration

The history database advances from schema 1 to schema 2 with a non-destructive migration that adds the `pinned` column and its compound index. Existing records default to unpinned. No destructive migration fallback is enabled.

Retention always protects active records first and pinned records second. The legacy Clear finished action also preserves pins.

## Evidence and release integration

support bundle format 18 reports pin counts, configured retention, and per-run pin state. The release gate now requires support format 18, and the Gradle source seal includes the workbench, query, and export formatter.

No Android resources are added. Phase 20 adds one non-exported activity declaration.
