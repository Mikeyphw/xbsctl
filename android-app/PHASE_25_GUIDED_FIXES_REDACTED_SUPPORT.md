# Phase 25: Guided fixes and redacted support bundle

Phase 25 turns Debug Health actions into explicit guided flows. Opening Debug Health remains passive. A repair runs only after the user opens a dedicated private activity, reviews the exact operation and risk class, and confirms it.

Allowlisted flows cover bounded root/compatibility refresh, canonical storage repair, explicit Room history initialization, interrupted transaction recovery, notification-channel repair, and event-monitor restart. Unknown or unsafe checks navigate to the responsible workbench instead of improvising a command. Committed configuration transactions are never rolled back automatically, and storage repair never uses broad chmod.

support bundle format 18 is always redacted before copy or share. The deterministic policy removes bearer credentials and key/value secrets, normalizes private filesystem roots, pseudonymizes package identifiers, email/IP addresses, and UUID-like identifiers, preserves correlation through stable short hashes, and records replacement counts. No raw support-export path is exposed.
Phase 25 r2 hardens redaction policy v2 for all Authorization schemes, quoted multi-word secrets, full private paths, two-component package names, IPv6 addresses, and modern UUID versions.
