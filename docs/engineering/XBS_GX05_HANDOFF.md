# GX05 — adversarial security, hosted performance and physical-readiness preflight

Source-pinned on GX04 HF01 PR #31 `be9fad6af9ae622c1a3dcb64eb968f4278dfb6c1`.
This milestone is an **emulator/hosted-only** preflight; the original 178 tasks,
131 cases and 26 findings are unchanged and not accepted by this overlay.

## Mandatory hosted evidence

- Seven Android API36 rootless security/FD/worker-smoke instrumentation methods: exact private
  root service/notification/boot receivers, TileService binding protection,
  untrusted Tasker intent, unverified daemon state refusal, plus bounded
  read-only FD/thread-growth smokes.
- Seven actual cold `am force-stop` / `am start -W` launches, parsed `TotalTime`
  values, and seven `dumpsys meminfo` total-PSS measurements. Strict nearest-rank
  p50 <= 8000 ms and p95 <= 15000 ms, max PSS <= 524288 KiB. Missing values,
  incomplete samples, non-x86 emulators, wrong SHA/run/attempt and failed or
  skipped instrumentation **refuse**. These are broad emulator budgets; not
  physical-device performance or battery-energy measurements.
- Two SHA256/size-verified, run/attempt/source-bound evidence payloads saved
  under existing `android-evidence/gx03/api36` artifact path. Existing Go/JVM,
  API26/API34, real SystemUI and process-death lanes remain mandatory.
- Fifty pure Python adversarial regression cases and exactly scoped source
  ancestry checks. No obsolete phase-specific gate is skipped or deleted.

## What is intentionally NOT qualified

Real ANR/StrictMode/worker-FD soak statistics, measured wakeup/polling rates,
real battery savings, physical ARM64/KernelSU/Magisk, destructive CPU/GMS/Wi-Fi
experiments, original R12 case assertion mapping, R13 release signing, and
GX06 full-stack integration remain **explicitly open**. The new regression
signals are useful but cannot substitute those separate acceptance gates.

## R11 consent and rollback

`XBS_GX05_CONSENT_MATRIX.json` defines a separate physical process requiring
an authorized disposable device, human approval, before/after hashes,
timeouts, restoration and independent recovery. `gx05_preflight.py
--inspect-inventory` only validates a previously captured JSON manifest; it
never opens ADB, runs root, installs, or changes a device.

## Source and launcher

Only Devtool Launcher v3.1's native immutable builder and native transaction
may apply the source payload. Required commit/tree/CLI blobs and payload hashes
are sealed when the user executes the canonical builder on a clean named
feature checkout. No ad-hoc Git patching or manual file copying is an allowed
apply mechanism. The starter bundle itself is not a hosted CI pass and is not
an already-applied Devtool package.
