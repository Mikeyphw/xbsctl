# XBSctl — GX gap-closing and improvement side roadmap v1

**Handoff revision:** 2026-11-14 · **State:** PLANNED, not implemented · **Authoritative original:** `archive/v2_original/XBS_FINAL_ROADMAP_v2.md`.

> The GX side track is a **temporary prerequisite track**, not a replacement or renaming of the original R00–R13 roadmap. GX task counts do not alter the original **178 tasks / 131 cases**. The original **R10 means hosted Android emulator/app journeys**, not stack integration.

## Mission and boundaries

First close the evidence-truth deficit, CI/tooling fragility, incomplete hosted Android journeys, missing native QS tiles and performance/physical-readiness gaps. Then hand control back to the original roadmap with exact residual task IDs, tests, dependencies and acceptance gates.

The original roadmap is **paused for new major milestones** while GX01–GX06 are planned and executed sequentially. Work in GX03/GX04 may implement existing original R10/R08 requirements, but these are cross-linked; the original phase labels and gates are never erased.

**Execution format:** one **consolidated Python3/Rich, source-pinned ZIP overlay per GX milestone**. Hotfix only after diagnosing real failure; use Devtool for plan/preflight, transaction and Actions journal; Termux/Zsh-safe launcher; no local Android Gradle; no automatic force-push, Git reset, root install, physical writes, merge or release. Follow GH runs by exact SHA and run/attempt; exit code 8 means pending.

**Truth tiers:** `SOURCE_REVIEW` / `HOSTED_GO_JVM` / `ROOTLESS_ANDROID_EMULATOR` / `PHYSICAL_READ_ONLY` / `PHYSICAL_REVERSIBLE_AUTHORIZED` / `SIGNED_RELEASE`. Advancing one tier never implies the next.

## Sequencing and deliverables

| Order | Side phase | Priority | Primary output | Original mapping |
|---|---|---|---|---|
| GX01 | Evidence truth and ledger reconciliation | P0 | One consolidated source-bound overlay + evidence receipt | R00, R06, R07, R09, R13 |
| GX02 | Control-plane and CI reliability improvements | P0 | One consolidated source-bound overlay + evidence receipt | R06, R07, R12 |
| GX03 | Hosted Android journey and compatibility gap closure | P1 | One consolidated source-bound overlay + evidence receipt | R05, R08, R10 |
| GX04 | Native Quick Settings and cross-surface UX closure | P1 | One consolidated source-bound overlay + evidence receipt | R03, R05, R08, R10 |
| GX05 | Adversarial, privacy, performance and physical-readiness preflight | P1 | One consolidated source-bound overlay + evidence receipt | R01, R04, R11, R12, R13 |
| GX06 | Stack integration, hardening signoff and original-roadmap handback | P1 | One consolidated source-bound overlay + evidence receipt | R00, R07, R10, R11, R12, R13 |

## Milestone detail and acceptance gates

### GX01 — Evidence truth and ledger reconciliation

**Priority:** P0 · **State:** PLANNED · **Original roadmap cross-links:** R00, R06, R07, R09, R13

- **GX01-01** Freeze main/stack source pins and keep the 10 known successful PR runs; detect any new head/base drift.
- **GX01-02** Reconcile every original R00–R13 task with introducing commit, actual tests, evidence, lane and explicit blocker.
- **GX01-03** Map all 131 original case IDs to exact test IDs, assertions, SHA, job, run/attempt and artifacts; leave unmatched cases open.
- **GX01-04** Review 26 audit findings and reclassify each as open, fixed-but-unverified, tested, or blocked; never erase history.
- **GX01-05** Regenerate living Markdown/JSON ledgers from one source and make consistency tests fail on missing IDs or fabricated PASS.
- **GX01-06** Preserve obsolete Phase31 918-file/237-contract evidence as archived; explicitly test no stale release proof is counted.
- **GX01-07** Bind emulator/Go/JVM/device/signature lanes independently; report unqualified fields even when workflow is green.

**Mandatory exit gate:** 178 unique original tasks and 131 unique original cases preserved; all have source-bound evidence or explicit OPEN/UNMAPPED; 26 findings reviewed; zero invented ACCEPTED/PASS; full CI green.

**Explicitly not qualified:** No physical/root, release, or automatic roadmap completion.

### GX02 — Control-plane and CI reliability improvements

**Priority:** P0 · **State:** PLANNED · **Original roadmap cross-links:** R06, R07, R12

- **GX02-01** Make Devtool the central plan/preflight/transaction/ACTIONS-journal controller; keep Python/Rich launchers thin and Zsh/Termux-safe.
- **GX02-02** Bound eventual-consistency GH run discovery by exact repo/workflow/branch/head SHA/event/start time/attempt, distinguish pending rc=8 from failed.
- **GX02-03** Persist resumable PR, run and artifact identifiers, distinguish not-yet-visible from not-created, prove no duplicate push/dispatch after interrupt.
- **GX02-04** Run full hosted Go/Android CI and differential affected-target checks against same exact source, without local Gradle.
- **GX02-05** Audit source-pinned action revisions, APK pairing, artifact digests, executor JDK/AGP/Gradle and workflow permissions.
- **GX02-06** Improve failure logs and artifact diagnostics: missing dependency, skipped test, zero test, bad coverprofile, unqualified device never false-green.
- **GX02-07** Test disposable Git rollback, upstream base drift, dirty checkout, symlink/path traversal, PR reuse and late-artifact arrival.

**Mandatory exit gate:** Existing canonical workflow remains green; fail-closed mutation tests and process-interruption tests pass; exact-run/PR journals resume; no local Gradle or device writes.

**Explicitly not qualified:** No novel Android feature or physical qualification.

### GX03 — Hosted Android journey and compatibility gap closure

**Priority:** P1 · **State:** PLANNED · **Original roadmap cross-links:** R05, R08, R10

- **GX03-01** Inventory original R10-01–13 and EM-001–018, EM-022–024; add missing exact method/fixture mappings.
- **GX03-02** Build fresh offline API36 real app + test APK pair identity with SHA/source/run/attempt, emulator-only authority and no root effects.
- **GX03-03** Exercise all MainActivity/navigation/editors/Tasker/FGS/Room journeys with nonempty AndroidJUnit results and negative assertions.
- **GX03-04** Prove real app process death with host-side kill between two instrumented stages, not just Activity recreation.
- **GX03-05** Capture real screenshots at 360dp/393dp, insets, light/dark, locale/RTL, rotation and accessibility with dimensions, hashes and redaction.
- **GX03-06** Add isolated API26 and API34 compatibility smoke shards alongside authoritative API36, with accurate supported Gradle/test task pairing.
- **GX03-07** Validate no overclaim: emulator proof never equals ARM64 KernelSU/Magisk, physical root, signed release, or measured battery benefit.

**Mandatory exit gate:** Original R10 gate reaches exact-APK and real-journey coverage with API36 counts/screenshots + API26/API34 shards; all evidenced cases mapped, remaining device-specific cases blocked explicitly.

**Explicitly not qualified:** No physical hardware effects.

### GX04 — Native Quick Settings and cross-surface UX closure

**Priority:** P1 · **State:** PLANNED · **Original roadmap cross-links:** R03, R05, R08, R10

- **GX04-01** Implement shared XbsTileController binding TileService only to observed daemon completion and secure eventbridge state.
- **GX04-02** Implement Saver, Pause/Resume, Profiles, Safe Mode; defer Restore Defaults behind explicit unlock and in-app confirmation.
- **GX04-03** Register TileService components and BIND_QUICK_SETTINGS_TILE manifest protection; test lockscreen/exported attack surfaces.
- **GX04-04** Enforce pending/offline/failed/recovery-required states, no optimistic active claim, and cross-Tasker/app/CLI synchronization.
- **GX04-05** Prove rapid taps, SystemUI listener lifecycle, process recreation and zero blocking privileged calls on main thread.
- **GX04-06** Hosted API36 QS/TileService integration plus Add Tile fallback and documented OEM/physical limitations.

**Mandatory exit gate:** All 14 original R08 tasks have evidence or explicit device-only blockers; four tiles + guarded fifth are implemented; EM-019–021 mapped to real hosted tests; no physical QS claims.

**Explicitly not qualified:** OEM/physical QS behavior remains R11.

### GX05 — Adversarial, privacy, performance and physical-readiness preflight

**Priority:** P1 · **State:** PLANNED · **Original roadmap cross-links:** R01, R04, R11, R12, R13

- **GX05-01** Close original R12 negatives with hostile inputs, credential/log redaction and source/run/attempt/artifact mismatch refusals.
- **GX05-02** Measure startup p50/p95, ANRs, StrictMode, worker/FD leaks and sustained fake-daemon resource budgets; baseline and variance in receipts.
- **GX05-03** Measure safe wakeup/polling rates and separate physical battery-impact claims; no fabricated savings or device-side tests.
- **GX05-04** Prepare R11 read-only inventory for API/ABI/KernelSU/module identity; no device installation or writes during preflight.
- **GX05-05** Create explicit consent matrix, reversible effects, before/after hash snapshots, rollback and cleanup receipts for future R11.
- **GX05-06** Reserve CPU/GMS/Wi-Fi disruption for separately approved disposable hardware; keep main device and automatic lab excluded.
- **GX05-07** Enforce R13 release refusal when any P0/P1 or physical/signing requirement lacks evidence.

**Mandatory exit gate:** Hosted R12 performance/security budgets and negative tests measured; R11 read-only protocol ready; RD/PF unexecuted device cases visibly blocked; no release or privileged effect performed.

**Explicitly not qualified:** Not actual physical acceptance.

### GX06 — Stack integration, hardening signoff and original-roadmap handback

**Priority:** P1 · **State:** PLANNED · **Original roadmap cross-links:** R00, R07, R10, R11, R12, R13

- **GX06-01** Rebase R10A naming as a side-track stack preflight only; its prepared ZIP is not an applied original R10 phase.
- **GX06-02** Verify exact PR ancestry and merge bases, source hashes, required jobs and artifact metadata across stacked PRs; no automatic merge.
- **GX06-03** Build ordered merge-readiness and conflict report against current main; require fresh full CI on intended aggregate SHA.
- **GX06-04** Final-diff 178 original tasks, 131 cases, 26 findings, all six GX gates and any unresolved original acceptance blockers.
- **GX06-05** Publish a signed-off *handoff state* with per-lane qualification; release qualification stays blocked until R11/R13 and operator approval.
- **GX06-06** Resume original roadmap by actual dependency and remaining task IDs only after GX gates are verified; never renumber R08–R13.

**Mandatory exit gate:** Exact-SHA integration/merge readiness and updated evidence ledger published; six GX gates validated; original work may resume with unresolved original tests/gates explicit; no automatic merge/release.

**Explicitly not qualified:** No PR merge, rooted device or signed release by this handoff.

## Resume contract / stop conditions

1. Implement GX01 before relying on per-case PASS. No new original phase can be considered ACCEPTED while its original test cases are unmapped.
2. Run GX02 reliability policy before expanding Android/emulator gates; source drift, wrong branch/base, no-test/skip ambiguity, lost auditor dependency and nonzero GitHub pending codes must fail or remain pending truthfully.
3. GX03 must not substitute generic Android JVM counts or source-only UI screenshots for real API36 APK/test pairing, full MainActivity journeys and independent API26/API34 smoke.
4. GX04 cannot claim Android SystemUI tiles from an in-app QuickActionsSheet; native `TileService` and manifest binding are mandatory.
5. GX05 does not start physical tests. A future R11 device run requires separate explicit approval, read-only inspection first, then precisely scoped reversible procedures. Disruptive CPU/GMS/Wi-Fi effects require disposable hardware.
6. GX06 produces a readiness report. It does **not** merge PRs, install modules, sign releases or mark R13 accepted; return to original R00–R13 backlog based on actual dependency gates.

## Workstream progress at handoff

- GX01–GX06: **all PLANNED, 0 applied, 0 hosted-qualified**; 40 proposed GX tasks (in addition to, not instead of, the original 178).
- R09F: verified hosted PR #26 / run #43650947189 / exact SHA e8647e8f80833d26fe6a6164725ae493f4a37301.
- R10A stack integration: prepared prior local ZIP, **not applied** (no matching remote PR or branch at review). Its work belongs under GX06, not original R10.
- Original Quick Settings R08 and physical R11 remain unqualified; full R10 app journeys/API26/API34 and R12 performance remain incomplete.

## Improving the workflow without proliferating overlays

Each GX milestone delivers source changes, regression and mutation tests, a Python3+Rich launcher, machine receipt schema, exact run watcher, auditor imports, and a self-contained handoff note **together**. Resist splitting into P0x fragments. A true CI failure leads to a consolidated HF within that milestone with durable source pins and recorded failed run.

## Handoff back to the original plan

After GX06 exits, restart the **unchanged original** roadmap at the earliest unresolved dependencies, especially R08/R10 qualification and R11/R12/R13. All 178 original tasks and 131 case IDs remain authoritative; side tasks are crosslinks, not replacements.
