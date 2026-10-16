# Phase 27 — Runtime Notification 2.0

- Shows authenticated, fallback, unavailable, and security-blocked event-bridge state.
- Uses immutable explicit PendingIntents and an explicit activity back stack.
- Persists a state-bound notification token before exposing mutation actions.
- Rejects expired, stale, duplicate, state-invalid, and bridge-security-failed actions.
- Converts foreground-service launch restrictions into durable evidence instead of crashing.
- Stops foreground services on platform timeout callbacks.
- Adds bounded runtime-notification evidence to Settings, Debug Health summaries, and support bundle format 18.
