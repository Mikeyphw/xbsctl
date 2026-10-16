# Phase 12: support bundle export

Phase 12 adds a bounded support bundle builder and copy/share actions from the Settings page.

The bundle is text-only and intentionally compact. It includes app/device metadata, cached root status, cached XBS runtime state, command-history counts, recent run summaries with bounded output tails, and recent event transitions. It avoids new storage permissions by using clipboard and Android's share sheet instead of writing files to external storage.

Validation remains Java-only: `:app:testDebugUnitTest` and `:app:lintDebug` are sufficient.
