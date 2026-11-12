# XBS R03C — Automatic-event reliability and Android API 36 read-only diagnostic

**Exact source base:** `c5fc976749df7830308dc88d9a4577a21c689c58` (R03B PR #10).

## Implemented

- Read-only request inspection: queued, claimed, immutable receipt, or missing. Invalid spool type/permissions and broken receipts fail closed. Inspection never repairs directories, writes receipts, or claims/replays requests.
- Automatic pending watchdog: ten-minute upper bound, old R03B timestamp migration, queued identity verification, no retry on absent/unknown evidence. Preserve pending ID and block automatic actions on ambiguous outcomes.
- Synthetic missed-transition and fault-injection tests built on real queue, planner and daemon automaticCycle Go source; inherited R02/R03 regression tests exercised.
- Opt-in read-only ADB sensor probe for Android API36 ARM64: `getprop` SDK+ABI, sysfs battery status/capacity, and `dumpsys power`. No root, writes, configuration changes, module changes, or profile transitions. Refuses unsupported API, ambiguous values and unsafe serial.
- No changes to privilege-restoration policy, boot-once journal or auto-enabled default.

## Remaining acceptance

R03C is *not* physical qualification: Android ADB probe is diagnostic only. Full repository precommit tests and hosted PR CI remain required. Real API36 rooted-device lifecycle, suspend/resume timing, packetless offline operation, boot race, and crash injection are future gated work.
