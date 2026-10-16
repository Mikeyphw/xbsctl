# Phase 03: storage paths and backup rules

Phase 03 clears the storage/backup lint cluster after the AndroidX foundation and locale cleanup.

## Changes

- `XbsConfigApi` now derives the external-storage root from `Environment.getExternalStorageDirectory().getPath()` instead of embedding `/sdcard` string literals.
- `CONFIG`, `LIST_DIR`, and `BACKUP_DIR` are centralized runtime constants so list editors, backup creation, and defaults stay in sync.
- `XbsConfigActivity` uses the canonical list directory for the default essentials allowlist path.
- The manifest now declares `android:dataExtractionRules` while retaining `android:allowBackup="false"`.
- `xbs_data_extraction_rules.xml` excludes root and external domains for both cloud backup and device transfer, keeping root/module state out of Android backup surfaces.

## Validation scope

Use focused Devtool task overrides for this warning cleanup:

```bash
--task :app:testDebugUnitTest --task :app:lintDebug
```

Expected lint impact:

- Clears the Java `SdCardPath` warning cluster for XBS config/list paths.
- Clears the manifest `DataExtractionRules` warning.
- Does not change root execution, Tasker integration, package visibility, or config schema semantics.
