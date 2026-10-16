# Phase 28 — Tasker and external-entry hardening

Tasker action, event, and condition configurations use bounded protocol-v2 bundles signed with a non-exportable Android Keystore HMAC key and pinned to the Tasker package signing certificate observed by the editor. Exported receivers validate the exact action, signature, installed host identity, sender package where Android exposes it, keys, value types, sizes, and typed CLI fields. Destructive commands require an explicit signed opt-in. Receivers use `goAsync()` and always finish. Completion intents must resolve explicitly to the signed Tasker host and cannot carry data URIs, ClipData, selectors, URI grants, or cross-package redirects. Boot reception is private. Support evidence is redacted through the final support bundle format 18.

## Sender-identity boundary

Android 14 and newer expose the sending package and UID, which are checked against the signed Tasker host. On Android 8–13 that identity is unavailable to manifest receivers, so the signed bounded configuration acts as a reusable bearer capability. It cannot be altered into a different command, argument set, host, or destructive permission, but it must remain protected inside Tasker.


## Literal configuration boundary

Action field values are literal and are covered exactly by the HMAC. Tasker variable-replacement metadata is intentionally not emitted because post-signing mutation would invalidate the security contract. Outbound event-query broadcasts are sent only to a Tasker package whose current signing certificate matches the certificate pinned when an editor configuration was saved. Completion variables are sanitized and bounded after machine-result expansion.
