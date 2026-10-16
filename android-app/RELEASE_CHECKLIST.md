# XBS Companion Android release checklist

## Host release gate

Export all four signing variables before creating a distributable artifact:

```bash
export ANDROID_KEYSTORE_FILE=/absolute/path/to/release.keystore
export ANDROID_KEYSTORE_PASSWORD='...'
export ANDROID_KEY_ALIAS='...'
export ANDROID_KEY_PASSWORD='...'
```

Run the sealed release gate and build:

```bash
python3 gradle-build.py . --task phase31FinalReleaseSeal --
```

`assembleRelease` and `bundleRelease` automatically depend on `verifyReleaseReadiness`, which also depends on `verifyReleaseSigning`.

## Device readiness gate

Before distribution:

1. Open More and Settings → Diagnostics → Release Readiness.
2. Run full Root Diagnostics so the evidence is fresh.
3. Open Configuration once to inspect or recover the latest transaction journal.
4. Refresh runtime state and the process sample.
5. Resolve every blocker. Review every warning deliberately.
6. Copy the readiness report or support bundle and retain it with the release notes.

A debug build may be operationally ready but remains an attention item. Distribution should use the signed release output.

- Confirm the Room history database is ready and the legacy run-history migration is complete.
- Open Activity workbench and confirm Room schema v2 is available, important runs can be pinned, and the configured retention matches the release policy.
- Open Command Center, verify compatibility evidence is current, and inspect at least one read-only and one state-changing generated preview.
- Confirm every Android-disabled catalog command presents workflow guidance and that destructive event-stop actions require the second confirmation.
- Open Quick Actions from Overview and verify validate, runtime state, diagnostics, and manual-event controls route to typed actions.
- Search More and Settings for monitor, notification, Tasker, cache, configuration, diagnostics, and version; confirm each result opens the correct single destination.
- Verify every toggle updates its state summary immediately and each selection dialog persists its choice.
- Confirm notification channels open directly from Notifications, cache/history controls appear only in Data and retention, and technical editors appear only in Configuration tools.
- Confirm About reports version 4.2.0 / versionCode 15 and Diagnostics reports support bundle format v18.
- Refresh Schema compatibility from Root Diagnostics and confirm the evidence source is live and fresh.
- Confirm Android contract, app version, and config version compatibility all pass.
- Review command and config schema digest status; any drift must be deliberate and documented.
- Verify a command omitted from a live fixture is disabled rather than executed through the static catalog.
- Verify an unsupported event field is preserved but not rewritten.
- Confirm Diagnostics reports support bundle format v18 with `[Compatibility]`, `[Debug health]`, `[Guided fix]`, `[Event bridge]`, and `[Redaction]` evidence.

- Open Debug Workbench before release and re-evaluate cached evidence.
- Confirm missing or stale root/runtime/process evidence is labeled incomplete or stale rather than current failure.
- Confirm the Debug Workbench does not trigger a root prompt, root command, capability refresh, or `/proc` sample on open.
- Resolve every Debug Workbench blocker and deliberately review warnings and unknown checks at their source workbenches.
- Confirm the support bundle includes a bounded `[Debug health]` section with stable check IDs and evidence-state labels.

- Phase 24 r2 makes Debug Health transitively passive: history, active-run, and transaction evidence use cache-only accessors; missing/stale passive evidence is explicit; a device regression protects Room, legacy preferences, and transaction cache from evaluation side effects.

## Phase 25: Guided fixes and redacted support
- Added private, explicit, allowlisted guided repair flows with risk labels and confirmation.
- Preserved passive Debug Health evaluation; repairs run only from the guided-fix activity.
- Upgraded support bundle format to v12 with deterministic secret/path/package/address/identifier redaction and bounded evidence.

## Phase 26: Authenticated event bridge and adaptive fallback
- Root-owned HMAC-SHA256 envelopes authenticate canonical event/status snapshots.
- Android enforces freshness, replay, duplicate, equivocation, key-rotation, and payload-size policy after root verification.
- Explicit bounded direct polling remains available only for non-security availability or freshness failures; authentication/replay failures fail closed.
- Cached bridge evidence is included in Debug Health, release readiness, diagnostics, Settings, and redacted support bundle format v18.
Phase 26 r2 uses an explicit availability/freshness fallback allowlist, serializes publication under a private file lock, rejects symlinked or insecure bridge state, and commits the Android replay cursor synchronously before returning authenticated data.
Phase 25 r2 hardens redaction policy v2 for all Authorization schemes, quoted multi-word secrets, full private paths, two-component package names, IPv6 addresses, and modern UUID versions.

Phase 27: Runtime Notification 2.0 adds authenticated transport presentation, state-bound action tokens, foreground-service launch evidence, timeout handling, and support bundle format 18.

- [ ] Phase 28 external-entry audit passes: all exported components justified, Tasker bundles signed/bounded, destructive opt-in enforced, completion intents explicit, receivers finish asynchronously.

Phase 29–30: combined manifest/path/stale-action security seal plus accessibility, adaptive layout, and bounded performance evidence.


Phase 29–30 r2 closes the final audit gaps: exact export-set evidence, verified Tasker signer pins, durable cancel-action replay protection, whole-screen 48dp/heading enforcement, expanded content containment, and runtime evidence-backed release readiness.


## Gradle 9.7.1 entry point

Do not run plain `gradle` if your PATH points to Gradle 8.x. AGP 9.3.1 requires Gradle 9.5.0 or newer; this project pins the fleet baseline to 9.7.1. Use Devtool's pinned Gradle when the wrapper is absent:

```bash
export GRADLE="$HOME/.cache/devtool/gradle-build/gradle/gradle-9.7.1/bin/gradle"
"$GRADLE" --version
"$GRADLE" --no-daemon --stacktrace \
  -Pphase31Overlay="$HOME/Downloads/mike_cli_suite_xbs_android_phase31_r3c_rebased_baseline_profile_hotfix_overlay.zip" \
  -Pphase31Baseline="$HOME/Downloads/mike-cli-suite-20260802-192532.tar.gz" \
  phase31FinalReleaseSeal
```
