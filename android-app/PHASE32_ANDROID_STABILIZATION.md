# Phase 32 — Android Stabilization and Bug Hunt Fixes

This phase fixes the bug-hunt findings found in `mike-cli-suite-20260803-135015.tar.gz`.

## Fixed

- Regenerated the complete Phase 31 expected source tree after the r3c hotfix and Phase 32 changes.
- Updated release instructions so users do not run PATH Gradle 8.6 by accident.
- Wrapped root commands with a root-side child PID file, process-group termination, and cancellation cleanup.
- Added stale active-run recovery and explicit force-finalize actions in Main and Activity Workbench.
- Removed runtime `/sdcard` path literals from XBS config/status paths.
- Rejected exported Tasker broadcasts when sender identity is unavailable instead of silently trusting legacy API gaps.
- Removed Room `allowMainThreadQueries`; Room now uses the app history executor.
- Changed security/runtime/external evidence stores to synchronous `commit()` for durable gate evidence.
- Refreshed visible release copy to 4.2.0 / support bundle v18 / Phase 31 final seal.

## Not proven by this overlay

This source overlay does not claim a Gradle build, Android SDK build, device instrumentation, signing, Macrobenchmark, Baseline Profile generation, or final 14-gate release-seal result. Those remain real-machine gates.
