# XBS Android Phase 07: command policy and timeout foundation

Phase 07 starts the root-execution reliability train after the AndroidX and warning-cleanup foundation. It does not introduce a foreground service yet; instead it makes the current runner safer and more explicit so the service migration has stable policy objects to reuse.

## Changes

- Added `ExecutionSource` for app, notification, Tasker, boot, and unknown request provenance.
- Added `MutationClass` for read-only, state-change, and destructive command policy.
- Added `TimeoutPolicy` with bounded defaults and clamp limits.
- Added `XbsCommandSpec` as the typed envelope used by `RootCommandRunner`.
- Replaced the unbounded cached-thread root executor with a fixed two-worker executor.
- Added timeout-aware command completion with exit code `124` for deadline expiry.
- Added explicit cancellation state with exit code `130` for user cancellation.
- Split stdout and stderr collection for root command runs while keeping bounded output tails.
- Persisted source, mutation class, and timeout seconds in run records for future Activity/Room phases.

## Validation

Use focused Gradle validation:

```bash
--task :app:testDebugUnitTest --task :app:lintDebug
```

`processDebugResources` is not required for this phase because Phase 07 only changes Java sources, docs, and tests.
