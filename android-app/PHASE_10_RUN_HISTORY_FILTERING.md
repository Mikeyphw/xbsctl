# XBS Android Phase 10: Run history filtering and retry

Phase 10 turns the command-result list into a retained command history surface.

## Changes

- Adds `RunFilter` for All, Active, Succeeded, Failed, Timed out, Cancelled, and Output views.
- Adds `RunRetentionPolicy` with a 120-record cap that keeps active runs when trimming old history.
- Adds command-history filter controls to the Activity page.
- Adds Clear finished and Clear all actions for local run records.
- Adds Retry for completed command records using the stored command envelope.
- Keeps stdout, stderr, error, source, mutation class, timeout, and run detail behavior from Phase 09.

## Validation

Use focused validation because this phase changes Java UI/helpers/tests only:

```bash
devtool --yes --copy --target xbs_android --host -r "$HOME/Code/mike-cli-suite" \
  apply-overlay "$HOME/Downloads/mike_cli_suite_xbs_android_phase10_run_history_filtering_overlay.zip" \
  --validate \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug
```


## r2 validation fix

The unit retention test now avoids `finishedAt=0`, which is the stored sentinel for unfinished/running records, so it validates newest finished-record trimming directly.
