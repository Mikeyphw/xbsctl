# Phase 26: Authenticated event bridge and adaptive fallback

Phase 26 protects the event/status transport against non-root tampering. The root daemon publishes a canonical format-v1 envelope signed with HMAC-SHA256 using a 32-byte root-owned key. The envelope includes a monotonic sequence, issue timestamp, key identifier, event/status payload, and MAC. The key is mode 0600 and never leaves the root environment.

Android invokes only the fixed hidden `xbs --json event-bridge` verifier. The CLI verifies the MAC with `hmac.compare_digest`; Android then enforces freshness, bounded payload size, monotonic sequence, duplicate identity, equivocation rejection, and verified key rotation with its own private replay cursor. The app never receives or exports the key.

If publication, Python, the verifier, or freshness evidence is unavailable, Android records the exact reason and may use the existing bounded direct root poll. Authentication, key-identity, replay, equivocation, and impossible-future failures never downgrade to unauthenticated data; they fail closed, return an unavailable snapshot, and remain explicit security evidence. Debug Health, Root Diagnostics, Settings, Release Readiness, and redacted support bundle format 18 expose transport state from cache only.

Threat boundary: this design detects non-root modification or replay of runtime files. A fully compromised root environment can access or replace root-owned key material and is outside the authenticity guarantee.
Phase 26 r2 uses an explicit availability/freshness fallback allowlist, serializes publication under a private file lock, rejects symlinked or insecure bridge state, and commits the Android replay cursor synchronously before returning authenticated data.
