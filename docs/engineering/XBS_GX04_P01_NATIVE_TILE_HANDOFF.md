# GX04 consolidated P01–P06 — Native Quick Settings and cross-surface UX

Base: exact hosted-green GX03 commit `aab3ff6d9e6cd9c343218606a5d8c6c619f1c487` (PR #30).
A single consolidated 20-path Devtool v2 overlay is applied to the new GX04 branch, stacked on GX03.

## Implementation

- Five real `TileService` classes protected by `android.permission.BIND_QUICK_SETTINGS_TILE`, with `ACTIVE_TILE` metadata and app-private refresh receiver for state and run updates.
- Shared `XbsTileDecision` and `XbsTileController`: authenticated fresh eventbridge+snapshot requirements, persistent pending records tied to a root-command run ID, observed post-command bridge sequence and target-state proof, failure and recovery status, cross-tile duplicate/rapid-tap blocking. An explicit in-app recovery acknowledgement can clear only a stopped warning and NEVER replays or marks the daemon command successful.
- Pause/Resume and Safe Mode use existing SuiteCatalog allowlisted commands with the existing RootCommandRunner, never direct raw privileged shell calls or optimistic success.
- Saver and Profiles can toggle a **user-selected existing XBS event** only. Users must select it from current authenticated `active`/`inactive` state; the event key is validated. Starting an inactive event is available from the unlocked tile. Stopping a configured active event is marked destructive by the existing catalog, therefore requires a separate in-app confirmation; it is NOT run by a direct tile tap.
- Restore Defaults is **not an existing XBS CLI command**. Its tile opens the app to review a two-step explicit **local tile shortcut reset**. It NEVER claims to restore daemon defaults or run a fabricated CLI command.
- SystemUI tile-add request for Android 13+ (API33), with manual Quick Settings Edit fallback; Android 14+ uses the required `PendingIntent` form of `startActivityAndCollapse`.
- Quick Settings service work uses an executor; root/evidence queries are not dispatched on the main thread. Protected against callbacks after service destruction and locked-display requests.
- Five real API36 Android instrumentation tests run under the existing mandatory GX03 host before process-death and screenshot evidence. They verify the actual installed manifest/permission/action and fail-closed policy, but **do not imply OEM or physically accepted SystemUI tap behavior**.
- 20 Python source tests and pure-Java decision regression harness. Original GX01/GX02/GX03 history audits, original 178 tasks/131 cases/26 findings, API26/API34, API36, no-root, and release refusal remain intact.

## Follow-up evidence and explicit blockers

Actual hosted Android compilation, API36 instrumentation receipt, real SystemUI tap/lifecycle and OEM/lockscreen results must be verified at the new GX04 PR SHA. Physical ARM64 root/KernelSU integration, battery benefit, and release signing remain R11/R13 and are NOT qualified. No root command runs on the model/container or local Termux; only GitHub-hosted Gradle and emulator qualification.
