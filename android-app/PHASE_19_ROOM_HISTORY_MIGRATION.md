# Phase 19: Room History Migration

Baseline: Phase 18 commit `b776fe3`.

Phase 19 replaces the command-history JSON array in `suite_runs` SharedPreferences with a versioned Room database while preserving the existing synchronous `RunStore` compatibility facade used by command execution, notifications, diagnostics, deep links, and the current programmatic View UI.

## Durable data

- `run_history` stores the bounded command envelope, state, timing, provenance, and bounded stdout/stderr/error tails.
- `config_transaction_history` stores the latest 32 bounded transaction summaries. The root-side transaction directories and snapshots remain authoritative.
- `history_metadata` stores migration completion evidence.
- SQLite write-ahead logging is enabled. The history facade keeps the current synchronous API until the lifecycle/repository UI migration lands.

## Legacy migration

The first database open reads the old `suite_runs/runs` JSON payload, parses valid records, trims them through the standing retention policy, and commits the records plus a `legacy_runs_v1` marker in one Room transaction. Only after that transaction commits is the old preference payload removed.

The migration is idempotent:

- process death before the Room transaction leaves the old payload available for retry;
- process death after the Room transaction is harmless because the metadata marker prevents duplicate import;
- malformed entries are skipped and counted rather than aborting valid history;
- run IDs remain primary keys, so retries cannot duplicate the same record.

## Failure behavior

Room failures are surfaced in release readiness and support bundle format 18. An in-process bounded emergency copy plus the legacy preference writer prevent the current command from disappearing if the database becomes temporarily unavailable. No destructive database fallback is configured.

## Apply

The artifact manifest uses `validation.mode: disabled` and `validation.failure_action: keep`.

```bash
devtool --yes --copy \
  -r "$HOME/Code/mike-cli-suite" \
  --target xbs_android \
  apply-overlay "$HOME/Downloads/mike_cli_suite_xbs_android_phase19_room_history_migration_overlay.zip" \
  --no-validate
```
