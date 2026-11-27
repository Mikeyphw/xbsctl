# R02 consolidated — command lifecycle / recovery / bounded transport

This overlay includes R02B (append-only claimed/applying phases, unknown-outcome holds) and the remaining R02 source work in a single patch from the R02A head. It adds bounded pre-execution deadlines, safe cancellation of **unclaimed** commands, read-only recovery inspection, startup configuration validation, status/event versioning, fail-closed status publication, and checked shutdown persistence.

## Invariants

* Every command uses a private, durable, no-overwrite filesystem spool; no root command is blindly replayed after a crash.
* A deadline is enforced only **before** execution; no Android command is killed at an arbitrary unsafe point.
* Cancellation is legal only while a request remains a `.json` queue item. Claimed `.work` is never cancelled or unilaterally cleared.
* Recovery inspection does not mutate receipts or release unknown outcomes. Persistent recovery mode and WAL are evidence, not a reason to assume the device is restored.
* No automatic unknown-outcome clearance: this requires a separately qualified device-aware operator procedure; full Android/API36 and physical crash testing remain mandatory R02 acceptance gates.

## New controller interface

`XBSctl [--json] recovery` shows read-only recovery evidence; `XBSctl [--json] cancel <request-id>` cancels an unclaimed request; `XBSctl --ttl SEC start manual` (or `reload`, etc.) attaches an optional bounded queue deadline. `--wait --timeout SEC` remains a client wait bound, not a kill/cancel mechanism.

## Acceptance truth

Source and Go tests are staged for execution, not declared as passed by this document. A successful Go workflow is **not** a rooted Android acceptance. The living ledger must remain in progress until all G-R02 gates are qualified.
