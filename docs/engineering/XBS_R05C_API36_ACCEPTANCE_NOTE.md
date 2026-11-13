# XBS R05C — API36 Companion/Tasker integration hardening

Source pin: `4bc0a9e94183a29a53b2a89b7b1af4f06ce9f4f5` (R05B hosted Go and Android JVM success, PR #16). This source patch does not install, flash, call `su`, or grant root permissions. No release/physical qualification is implied.

## Implemented

- Add mandatory `android-companion-api36` PR job depending on green Go and Android JVM jobs. It runs Android 16 (API36) Google APIs x86_64 emulator with rootless instrumentation and a *minimum 16 named test cases* with no skips. Failed or empty XML evidence refuses the job. Gradle build/install is hosted-only.
- Run the emulator lane against the debuggable test variant only when `-PxbsR05cEmulator=true`; preserve the existing release test variant outside the lane.
- Patch exported Tasker action/condition `goAsync` workers to convert hostile, malformed or unexpected `RuntimeException` input into explicit `FAILED` or `UNKNOWN`, recording bounded non-sensitive diagnostics and always finishing the PendingResult.
- Lock durable EventBridgeStore cursor commits to monotonically increasing sequence/digest per authenticated key. A duplicate with same digest is idempotent. An older or equivocated sequence refuses; a *root-verified* key rotation may reset sequence. A pure guard alone never authenticates an envelope.
- Ignore duplicate root-command `ACTION_RUN` service deliveries when an active owner already exists rather than treating them as an interrupted task and cancelling a live process.
- Execute real rootless Android tests for exported Tasker receivers, signed-payload tampering, persistent anti-replay cursor, owned command recreation, corrupt handoff, duplicate owner, manifest service export and main Activity resume. Inherit pending-service and Room main-thread smoke tests.
- Run Go race in hosted Linux lane. Publish a debug APK candidate with `SOURCE_SHA` and `SHA256SUMS`, never raw `logcat`, device state, user configuration or HMAC secrets.

## R05 qualifications deliberately open

The GitHub API36 lane must run and prove all 16 test cases at the new commit before any API36 hosted claim. Physical rooted process-tree cancellation, real Tasker host signature/UID integration across API levels, real user configuration recovery, visual screenshots and release signing require separate supervised acceptance. Debug APK is not a root module and not a signed production release.

## Why not run local Gradle?

User preference: Gradle builds and Android instrumentation execute only in GitHub Actions. Termux `python3 overlay.zip --self-test` runs real pure Java and Go fixtures, parser negatives, source and rollback tests; it does not invoke Gradle, ADB, device installation or elevated shell commands.
