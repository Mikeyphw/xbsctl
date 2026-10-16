# XBS Android Phase 11: Safe retry guards

Phase 11 hardens the Phase 10 retry path so command replay cannot silently rerun unsafe work.

## Changes

- Adds `RunRetryPolicy` as the single retry decision point.
- Blocks retry for active/cancelling runs.
- Blocks retry when no command was persisted.
- Sanitizes retry action IDs and avoids repeated `retry_` prefixes.
- Requires confirmation before replaying destructive root commands.
- Routes confirmed retry through the existing foreground root command service.

## Validation

Use Java-only focused validation:

```bash
devtool --yes --copy --target xbs_android --host -r "$HOME/Code/mike-cli-suite" \
  apply-overlay "$HOME/Downloads/mike_cli_suite_xbs_android_phase11_safe_retry_guards_overlay.zip" \
  --validate \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug
```
