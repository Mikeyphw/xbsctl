# XBSctl — progress snapshot and scope correction (2026-11-14)

**Authoritative original:** `archive/v2_original/XBS_FINAL_ROADMAP_v2.md` (14 phases, 178 tasks, 131 cases).
**Current implementation stack:** `Mikeyphw/xbsctl` e8647e8f80833d26fe6a6164725ae493f4a37301 (R09F PR #26); **main** f048d2d5526a3d0d7632e477b6b4f052e41e30df.
**Evidence:** GitHub PR/Actions job metadata and source inspection; no physical/root/test signing was performed in this handoff.

## Exact hosted evidence (latest tested heads, not raw PR names)

| PR | Stage | SHA | Hosted run | Status |
|---:|---|---|---:|---|
| #17 | R05C | `133f1923bb4e` | [43495194055](https://github.com/Mikeyphw/xbsctl/actions/runs/43495194055) | SUCCESS, Go+JVM+API36 |
| #18 | R06A | `b87d1bf18fd2` | [43500262306](https://github.com/Mikeyphw/xbsctl/actions/runs/43500262306) | SUCCESS, Go+JVM+API36 |
| #19 | R06B | `1f646ce260f3` | [43554367907](https://github.com/Mikeyphw/xbsctl/actions/runs/43554367907) | SUCCESS, Go+JVM+API36 |
| #20 | R07A | `0e3de1e2f228` | [43603270776](https://github.com/Mikeyphw/xbsctl/actions/runs/43603270776) | SUCCESS, Go+JVM+API36 |
| #21 | R09A | `401d972c1c85` | [43606856091](https://github.com/Mikeyphw/xbsctl/actions/runs/43606856091) | SUCCESS, Go+JVM+API36 |
| #22 | R09B | `6d23d6adc905` | [43610030758](https://github.com/Mikeyphw/xbsctl/actions/runs/43610030758) | SUCCESS, Go+JVM+API36 |
| #23 | R09C | `ffe8bc2c2efb` | [43616766849](https://github.com/Mikeyphw/xbsctl/actions/runs/43616766849) | SUCCESS, Go+JVM+API36 |
| #24 | R09D | `f82182229939` | [43646824836](https://github.com/Mikeyphw/xbsctl/actions/runs/43646824836) | SUCCESS, Go+JVM+API36 |
| #25 | R09E | `9f6e58473fca` | [43648376860](https://github.com/Mikeyphw/xbsctl/actions/runs/43648376860) | SUCCESS, Go+JVM+API36 |
| #26 | R09F | `e8647e8f8083` | [43650947189](https://github.com/Mikeyphw/xbsctl/actions/runs/43650947189) | SUCCESS, Go+JVM+API36 |

A successful run is a hosted-lane qualification at its exact commit, **not** physical hardware acceptance, a merge, or a signed release.

## Latest R09F details

- Go/JVM/API36 mandatory jobs: success on run 43650947189 (attempt 1) at e8647e8f80833d26fe6a6164725ae493f4a37301.
- R09F: 6 named tests, 88 subtests; repeats 18 roots / 264 subtests; race 6 roots / 88 subtests.
- R09F full internal Go statement coverage: **72.09%**; seven risk packages, zero below the prior 50% floor. R09D initially measured 71.29%, R09E 72.07%; these figures belong to different commits.
- Three expected hosted artifacts published in latest run: module, Android JVM counts and API36 app/test evidence. Historical artifact bytes are **not** rehashed by this documentation pass.

## Original phase progress — do not conflate status with acceptance

| Original phase | Tracking status | Current evidence / open gate |
|---|---|---|
| R00 | IN_PROGRESS | Main retains original baseline; legacy manifest/Phase31 evidence still not fully reconciled. |
| R01 | IN_PROGRESS | Durable effect ownership and crash/fault tests in cumulative hosted code; physical sysfs/PM effects unverified. |
| R02 | IN_PROGRESS | Durable queue/ack/recovery and crash matrix exercised in hosted Go, not physical lifecycle. |
| R03 | IN_PROGRESS | Event lifecycle and chronology source present, real charging/screen observations not accepted. |
| R04 | IN_PROGRESS | Module artifact and offline stage safety; physical KernelSU/Magisk install/upgrade untested. |
| R05 | IN_PROGRESS | JVM + API36 partial app/Tasker/Room tests; complete original R05 app-end-to-end gate open. |
| R06 | IN_PROGRESS | R06A/B exact-source native Devtool hosted checks passed, case-level mapping open. |
| R07 | IN_PROGRESS | R07A immutable CI/mandatory gates green; full optimization parity and historical release contract mapping open. |
| R08 | NOT_STARTED | No native Quick Settings TileService found in source/manifest. |
| R09 | HOSTED_VERIFIED | Hosted R09A-F green, 72.09% full internal statement coverage across seven risk packages; case mapping pending. |
| R10 | IN_PROGRESS | Real API36 instrumentation green but original 13 R10 tasks and broader emulator matrix not fully qualified; R10A preflight is not R10. |
| R11 | NOT_STARTED | No physical rooted-device acceptance; read-only inspection and separately consented reversible effects remain. |
| R12 | IN_PROGRESS | Rootless fault/security tests pass; measured startup/ANR/wakeup/battery/resource budgets not complete. |
| R13 | NOT_STARTED | Release signing, full matrix coverage, physical and operator signoff absent; not release-qualified. |

## Tracking accounting

- Original task IDs preserved: **178**; current handoff tracking: **7 HOSTED_VERIFIED**, **106 IN_PROGRESS**, **65 NOT_STARTED**; **0 ACCEPTED** against the original phase/case definitions.
- Original 131 test-case IDs preserved verbatim with `NOT_RUN` pending **individual original-case evidence mapping**. This is a reconciliation status, not a claim that zero real tests were executed.
- Latest live repository ledger before handoff was stale for R06–R13: it retained R06–R13 as NOT_STARTED, though R06–R09 hosted code existed. The v3 progress snapshot now records those observed hosted lanes, without inventing assertions.
- **Provenance:** 65 IN_PROGRESS task IDs matched the current GitHub ledger; 48 additional R06/R07/R09/R10/R12 task workstreams are now acknowledged by hosted code/CI (seven at HOSTED_VERIFIED, the remainder IN_PROGRESS). No task has been ACCEPTED.
- Counts above are **tracking estimates**, not a percent-complete or a replacement of original test/acceptance definitions.

## Confirmed gaps and roadmap drift

1. Original R08 QS: no native TileService or five tile registrations found; QuickActionsSheet is not a QS tile.
2. Original R10: API36 instrumentation passes but no dedicated API26/API34 compatibility workflow or complete original journey matrix demonstrated.
3. R11 physical: no approved read-only, reversible rooted acceptance evidence.
4. R12: targeted security tests present, budgeted performance/wakeup/battery results missing.
5. R13: no signed release, final full gate or completed 131-case evidence ledger.
6. **Naming correction:** locally prepared `XBS_R10A_CONSOLIDATED_STACK_INTEGRATION.zip` is an integration audit, not original R10; no matching remote PR/branch at review.
7. Original backlog remains on stacked, open feature PRs; `main` is still at f048d2d5526a3d0d7632e477b6b4f052e41e30df; do not assume integrated changes are on main.

## Side-roadmap first rule

Run GX01 → GX02 → GX03 → GX04 → GX05 → GX06 before starting a new original roadmap milestone. GX work may close original R08/R10 acceptance gaps, but retains original IDs. No automatic merges, rooted device writes, local Gradle or release signing.

## Decisions needed before original-roadmap resume

Require the GX exit receipts, original-case mapping, full hosted regressions, explicit physical test approval if relevant, updated integration/merge plan and a blocked-claims review. Remaining R11 and R13 steps require their own independent acceptance.
