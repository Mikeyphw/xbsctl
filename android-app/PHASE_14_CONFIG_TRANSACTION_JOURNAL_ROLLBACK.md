# Phase 14: Configuration Transaction Journal and Rollback

Phase 14 replaces independent configuration and list-file writes with one serialized root-side transaction.

Each save now:

1. accepts only `XtremeBS.json` and direct children of the canonical managed list directory;
2. rejects nested, traversal, symbolic-link, non-regular, oversized, and oversized-file-set targets;
3. acquires one root-side transaction lock and safely reclaims locks left by a dead shell;
4. snapshots the complete old file set, including explicit absent-file markers;
5. stages every write and records new SHA-256 digests in the immutable plan and old SHA-256 digests in the incremental snapshot journal;
6. validates staged JSON, promotes configuration writes, list writes, and managed deletions as one set;
7. runs the real `xbs --json validate` path, reloads XBS when requested, and verifies every promoted file;
8. automatically restores and verifies the complete old set when staging, promotion, validation, reload, or verification fails.

The Configuration screen exposes the latest journal state, latest-only rollback, and interrupted-save recovery. Rollback is refused when a newer transaction exists or a managed file changed after commit. A failed rollback re-applies and verifies the committed set. Recovery is idempotent and remains retryable after a partial recovery failure.

The essentials editor now keeps app-owned writes inside the canonical managed list directory and provides a one-tap managed-path reset.

support bundle format 18 includes the cached transaction summary without performing another root probe. Failed transactions refresh the cached summary so evidence remains available. The Phase 13 foreground-service permission check uses permission-name strings, removing both API-28 `InlinedApi` warnings on minSdk 26.
