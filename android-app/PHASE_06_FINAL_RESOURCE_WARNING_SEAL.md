# XBS Android Phase 06: final resource warning seal

Phase 06 removes the final warning-only resource residue after Phase 05 and keeps the cleanup safe for Devtool's artifact-scoped Git commit.

## Changes

- Removed `res/drawable/ic_launcher_legacy.xml`, which is no longer referenced after the adaptive launcher icon migration.
- Added a narrow module lint policy for `src/main/res/mipmap-anydpi-v26` so a stale empty local directory left by earlier rollbacks cannot keep reporting `ObsoleteSdkInt`. Clean checkouts naturally omit this untracked directory.
- Kept `res/mipmap/ic_launcher.xml` and `res/mipmap/ic_launcher_round.xml` as adaptive icons with monochrome support.

## Validation

Use focused Android validation tasks:

```bash
--task :app:processDebugResources --task :app:testDebugUnitTest --task :app:lintDebug
```

The resource-linking task is included intentionally because this phase touches launcher-related resource cleanup and lint policy.
