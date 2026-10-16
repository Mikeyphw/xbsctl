# XBS Android Phase 05: Warning polish

Phase 05 is a warning-cleanup pass on top of Phase 04.

## Changes

- Guard `BootReceiver` so it only handles `Intent.ACTION_BOOT_COMPLETED`.
- Keep Tasker receivers exported for the plug-in contract while adding scoped lint justification.
- Replace count summary strings with Android plural resources.
- Reuse notification and Tasker labels from string resources.
- Suppress the intentionally dynamic Monet color lookup in `AppPalette`.
- Replace the legacy unqualified `mipmap` launcher selectors with adaptive launcher icons and monochrome support.
- Remove obsolete launcher fallback resources and unused warning-era icons/colors/styles.

## Validation

Use focused Gradle validation:

```bash
--task :app:testDebugUnitTest --task :app:lintDebug
```
