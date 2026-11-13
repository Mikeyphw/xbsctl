# XBS R05A — daemon acknowledgement, Tasker completion and foreground ownership

Source parent: `b94cc91ddd716b83c03834031ea536699e6b3f3e` (R04 PR #14). This source-pinned overlay is **stacked** on the exact R04 branch, not applied onto R03D or main.

## Delivered

* Go controller now echoes the exact queued request ID and does not report `applied` until the matching daemon ack **and** an observed runtime state change agree; it returns unknown (nonzero) on absent/mismatched observations. A config reload requires a changed `last_reload_time`. This is a conservative, read-only observation, **not** an end-to-end digest of the newly committed config (left for R05B).
* Android command dispatch appends explicit `--json --wait --timeout 12` to supported XBSctl/xbs controller mutations, without modifying unrelated commands. A bounded receipt verifier checks request ID, action, observed active events/mode/reload time, and rejects queued/missing/unknown/error receipts even if the command process exited with code 0.
* Tasker receives `RESULT_OK` only for matched applied controller receipts, or for successful verified noncontroller effects. A queued controller command can never return success. Adds `%suite_status` and `%suite_request_id` without leaking tokens.
* Run history distinguishes failed, cancelled, pending, and recovery-required outcomes; uncertain executions cannot be retried from history. The foreground worker preserves single-owner run accounting: when abort/timeout wins before a worker starts, the worker returns without decrementing again.
* Repeated authenticated Tasker broadcasts with the same signer/config/action within 1.5 seconds are coalesced in bounded process memory. This is a UI/dispatch throttle, **not** durable idempotency; the daemon's request receipt is authoritative.
* Transactional config reload now **waits** for a confirmed daemon reload and observed state. If the reload was started and subsequently fails or times out, the shell retains transaction files and a `recovery_required` journal instead of blindly restoring old config. Other pre-reload failures still use the existing verified rollback path. R05B will finish transaction-generation binding, contention and recovery state machines.
* New hosted Actions `android-companion-jvm` job compiles and runs the JVM unit suite. Host JVM tests are distinct from physical/API36 instrumentation acceptance.

## Still open

R05B: exact transaction/config revision digest and generation binding, stale-pointer/rollback adversarial acceptance, credential protection, cross-process durable deduplication. R05C: API36 emulator instrumentation, FGS permission/denial/restart device behavior, main-thread StrictMode, UID/signer/Keystore boundary and instrumented Tasker action/condition assertions. R05A alone does not qualify R05 as completed; root-device and physical testing are not performed.

## Safe application

`python3 XBS_R05A_CONSOLIDATED_COMPANION_TASKER_20261113.zip --self-test` runs synthetic fixtures without the device.
`--check` requires a clean checkout at the exact R04 parent commit. `--push --pr --watch` does not install anything or run privileged Android operations. The launcher creates `feature/xbs-r05a-companion-tasker-20261113` and targets the R04 branch for review. Gradle runs **only on hosted GitHub Actions**.
