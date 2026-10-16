# Phase 21: Command Center and Quick Actions

Baseline: Phase 20 Activity Workbench commit `beedf20`.

Phase 21 turns the static CLI list into a typed Command Center and adds a compact Quick Actions sheet for the operations used most often on-device.

## Command Center

- Searches command titles, IDs, categories, executable paths, descriptions, and expected effects.
- Groups the complete published XBS and XBSctl catalog by purpose.
- Shows availability, risk, expected effect, and bounded timeout before a form opens.
- Generates an immutable generated command preview from the catalog schema and validated form values.
- Launches catalog operations through `XbsCommandSpec.fromCatalog` and `RootCommandRunner.runCatalog`.
- Rejects forged catalog objects and commands that are intentionally unsupported from Android.
- Keeps CLI configuration writes and external editor commands visible but disabled, directing users to the transaction-backed native editor.
- Keeps shell completion/manpage installation visible but disabled with terminal-environment guidance.
- Requires an explicit second confirmation for destructive operations.

## Quick Actions

The Overview, Events, and Settings surfaces can open one compact bottom sheet containing:

- Validate configuration
- Reload runtime
- Pause or resume runtime
- Enter or exit safe mode
- Start or stop the manual event
- Open all event controls
- Open Root Diagnostics
- Open the full Command Center

Every executable Quick Action maps to `SuiteCatalog` and the typed root-command service. The sheet never accepts arbitrary shell text.

## Compatibility behavior

Fresh Root Diagnostics evidence disables XBS or XBSctl actions when the required executable is missing. Stale or absent evidence is labeled as unknown rather than silently reported as supported, and the UI directs the user to refresh diagnostics.

No Android resources or manifest declarations are added in this phase.
