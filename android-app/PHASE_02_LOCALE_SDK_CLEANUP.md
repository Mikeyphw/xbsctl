# Phase 02: Locale and min-SDK cleanup

Phase 02 trims the first lint-warning cluster after the AndroidX/Material foundation.
It keeps behavior stable while making string normalization and Android API branching explicit.

## Scope

- Replace internal `toLowerCase()` and `toUpperCase()` calls with `Locale.ROOT`.
- Remove Android 25 fallback paths from foreground-service and notification-channel code now that `minSdk` is 26.
- Keep runtime guards that still matter above the minimum API level, including notification permission checks and API 31 foreground-service behavior.
- Preserve the root-native runtime, Tasker protocol, notification channel IDs, event monitor behavior, and current UI navigation.

## Why this exists

AGP 9.3 lint reports `DefaultLocale` and `ObsoleteSdkInt` warnings twice through Devtool's relative and repository-absolute report streams. This phase eliminates the source causes rather than suppressing them.

## Files changed

- `MachineResult.java`
- `MainActivity.java`
- `ResultFormatter.java`
- `XbsEvent.java`
- `EventMonitorService.java`
- `NotificationController.java`
- `TaskerProtocol.java`
- `tests/test_xbs_android_phase02_locale_sdk_cleanup.py`

## Validation

Recommended focused Devtool validation tasks:

```bash
devtool --yes \
  --copy \
  --target xbs_android \
  --host \
  -r "$HOME/Code/mike-cli-suite" \
  apply-overlay "$HOME/Downloads/mike_cli_suite_xbs_android_phase02_locale_sdk_cleanup_overlay.zip" \
  --validate \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug
```

The overlay also carries a Python artifact validator that checks the exact source contract for locale and min-SDK cleanup before Android tasks run.
