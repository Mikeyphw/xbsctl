# XBS Android Phase 04: UI widget and string-resource cleanup

Phase 04 clears the next UI lint-warning cluster on top of Phase 03. It keeps the existing native programmatic View UI intact while aligning widgets and dynamic labels with the AndroidX/AppCompat foundation.

## Changes

- `UiKit.toggle` now returns `SwitchCompat` and constructs AppCompat switches.
- Activities that keep references to toggle controls now use `SwitchCompat` and preserve existing checked-change behavior.
- Reported dynamic `setText` compositions now use Android string resources with placeholders.
- Process/app summaries, navigation labels, read-only path labels, status rows, and event-save labels are resource-backed.
- `android:fullBackupContent` now points to `xbs_full_backup_content.xml`, matching the Android 12+ data-extraction rules with pre-Android-12 backup metadata.

## Validation

Use focused Android validation for this source-only warning cleanup:

```bash
--task :app:testDebugUnitTest --task :app:lintDebug
```

The overlay also includes source-contract tests covering switch migration, string-resource routing, and backup-rule compatibility.
