# Phase 32B: Runtime bridge parser hotfix

Phase 32B fixes the on-device runtime failure where the Overview screen reported:

```text
Authenticated event bridge rejected the snapshot: Root verifier output could not be parsed: Value usage of type java.lang.String cannot be converted to JSONObject
```

## Runtime fix

- The Android verifier command now tries the module-owned XBS binary before `/system/bin/xbs`, avoiding stale or shadowed command shims when both paths exist.
- The shell verifier wrapper no longer forwards raw CLI usage text to Android. If an executable emits non-JSON usage output, the wrapper returns a bounded JSON verifier envelope with `reason_code=verifier_usage`.
- `EventBridgeEnvelope` classifies CLI usage output as an availability failure and keeps random non-JSON machine output as `invalid_machine_output`, which still fails closed.
- `EventBridgePolicy` allows fallback only for `verifier_usage` and the existing availability/freshness cases. HMAC, replay, equivocation, timestamp, key, symlink, payload, and cursor failures remain security failures.

## Regression coverage

- JVM tests cover raw CLI usage, JSON-shaped usage, random non-JSON output, and verifier command ordering.
- Python source contracts check the parser classification, fallback allowlist, command wrapper, and fail-closed behavior.
- The Phase 31 complete source-tree manifest and exact Python contract count were refreshed after the hotfix.

## Not claimed here

This source overlay does not claim a Gradle build, device instrumentation, root execution, signing, Macrobenchmark, Baseline Profile generation, or final 14-gate release seal run.
