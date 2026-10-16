# Phase 18: Release and readiness seal

Baseline: Phase 17 commit `361696f`.

## Deterministic readiness gate

The app now exposes a dedicated Release Readiness screen backed by a pure-Java policy. Every check has a stable identifier and one of three severities: blocker, warning, or pass. The gate never performs an implicit root command. It evaluates bounded evidence already held by the root diagnostics, runtime, process, transaction, run-history, notification, and settings stores.

Release blockers include stale or missing root diagnostics, unavailable root access, missing XBS binaries, unsafe storage paths, incomplete foreground-service declarations, active command records without their service, transaction states that require recovery, unavailable runtime state, invalid release metadata, an old target SDK, and an obsolete support-bundle schema.

Warnings cover debug builds, notification settings, configured-but-stopped monitoring, stale runtime/process samples, partial `/proc` visibility, and a transaction journal that has not yet been cached. Warnings do not masquerade as passes, but they do not block a signed release artifact.

## Guided readiness workflow

Settings and Root Diagnostics link directly to the readiness gate. Each actionable issue routes to the appropriate screen: Root Diagnostics, Configuration journal, runtime Overview, notification settings, monitor settings, or Process Monitor. The gate can be copied independently, and the full support bundle includes the complete itemized result.

## Release metadata and build seal

Phase 18 establishes the release-readiness gate. In the final sealed tree, the app is version `4.2.0` / versionCode `15`; the Gradle `verifyReleaseReadiness` task depends on release-signing verification and checks the version, target SDK, and required readiness sources before `assembleRelease` or `bundleRelease` can run.

support bundle format 18 adds a `[Release readiness]` section with the gate state, counts, build variant, version, and every stable check result.

## Artifact workflow

The overlay sets `validation.mode` to `disabled` and `validation.failure_action` to `keep`. Apply with `--no-validate`.
