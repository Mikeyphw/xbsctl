# Phase 08: foreground command service and cancellation

Phase 08 moves root-command ownership out of transient activity/receiver calls and into a dedicated foreground service.

## What changed

- Added `RootCommandService` as the foreground owner for active root commands.
- `RootCommandRunner.run(...)` now records the run, posts its run notification, and starts the foreground service.
- The service uses a bounded two-worker executor, matching Phase 07's concurrency policy.
- Command cancellation now records a `cancelling` state before the terminal cancelled result is written.
- Notification cancel actions route through the context-aware cancellation path.
- A dedicated foreground-service notification remains visible while root work is active.
- `CommandLaunchResult` now carries the generated `runId` for future UI cancellation/inspection controls.

## Validation focus

Phase 08 changes Java execution/service code and the manifest. Use:

```bash
--task :app:testDebugUnitTest --task :app:lintDebug
```

Expected result: unit tests pass, lint reports zero errors and zero warnings.
