# Phase 09: active command UI and run details

Phase 09 connects the foreground command service from Phase 08 to visible in-app controls.

## What changed

- Added `RunDetailFormatter` to centralize active/completed command formatting.
- `RunStore.active(...)` now exposes running and cancelling commands for UI rendering.
- The Overview page shows an active root-command banner while foreground work is running.
- The Activity page now starts with an Active root commands section.
- Command cards show source, mutation class, timeout, output availability, and a direct Cancel button while active.
- Run notifications that open the app with `run_id` now reveal the matching run detail dialog.
- Run details keep stdout, stderr, and error separate and offer Copy plus Cancel-run controls when applicable.

## Validation focus

Phase 09 changes Java UI/helpers/tests only. Use:

```bash
--task :app:testDebugUnitTest --task :app:lintDebug
```

Expected result: unit tests pass and Android lint remains at zero errors / zero warnings.
