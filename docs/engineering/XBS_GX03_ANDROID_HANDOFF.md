# GX03 — Hosted Android journeys and compatibility, source-pinned handoff

**Base:** GX02 PR #28, exact HEAD `ea46616247484edc26bcd9e47e0a5fd8f4565208` (green hosted run `43675851257`).

GX03 is an **isolated hosted Android emulator** milestone, not root/device/release acceptance. The original R00–R13 task ledger and all 131 original test cases are unchanged. No original EM case is promoted solely from this overlay. GX02 introducing-commit proof is preserved on the stacked GX03 HEAD rather than wrongly requiring GX03 HEAD to be GX02 HEAD.

## Consolidated implementation

- AndroidJUnit real launcher navigation across Overview/Events/Processes/Activity/Settings, hostile route input, and a separate host-driven persistent pending-command process-death stage.
- Real exact app/test APK pair attested with package names, matching certificate SHA-256, file digests, expected commit, run ID and run attempt.
- Actual API36 screenshots at 360dp and 393dp (480 dpi), light/dark requested modes. Only status/navigation bands are redacted; full locale/RTL and accessibility acceptance remain open. Raw screenshots are not uploaded.
- Dedicated PR-triggered API26 and API34 x86_64 emulator jobs with real smoke/Tasker boundaries. Never claim an ARM64 KernelSU/Magisk device from hosted x86_64.
- Source-only adversarial guards retain GX01/GX02 Go/JVM/API36 mandatory lanes, unchanged legacy R05c tests, and preserve all original 178 tasks, 131 cases and 26 findings.

## Explicit remaining blockers

- R10-05..07 full editor CRUD, Tasker edit/return/condition journeys; R10-08 full foreground-service/notification cancellation; R10-09 Room migration acceptance already has existing evidence but needs exact GX03 mapping and re-execution; R10-10 locale, RTL, accessibility, rotation and insets do not yet have full real screenshot assertions.
- Original EM-001..018 and EM-022..024 remain **NOT_RUN/UNMAPPED in the canonical GX01 matrix** pending exact method, fixture and artifact receipts; new tests supply partial candidate evidence only.
- Both API26/API34 hosted shards and the new API36 requalification must independently pass; if a shard is absent or fails, GX03 remains open.
- No physical-root, signing, power savings, or release qualification. Do not merge automatically.

## Future

GX04 native Quick Settings, GX05 performance/security + physical-readiness preflight, GX06 stack integration; return to original R08–R13 by exact residual IDs, without renumbering.
