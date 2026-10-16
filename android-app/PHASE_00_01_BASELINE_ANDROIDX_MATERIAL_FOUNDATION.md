# XBS Android Phase 00 + 01

## Baseline contract and AndroidX/Material foundation

This combined foundation overlay freezes the existing XBS Companion behavior and moves the native app onto the platform required by the later UI and reliability phases.

## Behavior preserved

- Root-native `su -c` execution with typed and allowlisted XBS commands
- Native event create, clone, rename, edit, delete, start and stop
- Protected built-in event identities
- Simultaneously active event handling
- Configuration and essentials editors
- Installed-app and process-rule pickers
- Live process sampling
- Complete XBS/XBSctl command catalog
- Tasker actions, events and states
- Foreground runtime monitoring and notifications
- Local run and event history

## Foundation changes

- Enabled AndroidX without Jetifier.
- Added AppCompat, Material Components, Activity, Lifecycle, SavedState, RecyclerView and Room dependencies.
- Added `XbsApplication` with opt-in Material dynamic colors.
- Added `XbsActivity`, the shared AppCompat and edge-to-edge base class.
- Migrated every concrete activity to `XbsActivity` without changing its workflow.
- Replaced framework AlertDialog imports with the AppCompat implementation.
- Introduced a Material 3 dark theme while retaining the existing compatibility theme alias.
- Removed the obsolete Termux wording from permission-denied guidance.

## Deliberately deferred

This phase does not add Room entities, RecyclerView screens, ViewModels, fragments, a Compose runtime, command-service behavior, or the final visual redesign. Those changes depend on this foundation and land in later overlays.

## Rebase provenance

This overlay revision was rebuilt against `mike-cli-suite-20260730-103616.tar.gz`; the Android toolchain declarations have since been standardized fleet-wide to AGP 9.3.1 / Gradle 9.7.1 while the phase behavior remains unchanged.

## Catalog contract correction (r2)

The current native command catalog contains 40 entries: 38 executable CLI commands and 2 native-only navigation entries (`config_native` and `event_manage`). The pre-existing unit test still asserted the retired 43/41 totals. Phase 00+01 r2 corrects those stale totals while retaining the named-command and command-rendering assertions.


## Tasker back-navigation correction (r3)

The Tasker action, event, and state editors now register their save-on-back behavior through `XbsActivity.registerXbsBackHandler`, which delegates to AndroidX `OnBackPressedDispatcher`. This preserves Tasker result saving for toolbar and gesture back navigation without overriding deprecated framework back callbacks.


### r4 AGP 9.3 lint policy hotfix

The overlay now satisfies the stricter AGP 9.3 lint gate for the supplied snapshot: API-27-only navigation-bar styling lives in `values-v27`, the runtime update receiver is explicitly non-exported through AndroidX Core, and the root/process rule package-visibility permission is documented with a narrow lint suppression.


### r5 Devtool artifact-history staging hotfix

The repository now explicitly ignores Devtool's local artifact-history ledger and generated build-artifact directory. This keeps automatic overlay commits scoped to the actual payload after validation, preventing `git add` from failing when `.devtool/artifacts.json` has not been opted into repository tracking.
