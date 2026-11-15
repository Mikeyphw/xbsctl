# Handoff v3 — evidence reconciliation protocol

1. Keep `archive/v2_original/` byte-identical. Original task IDs R00–R13 and case IDs from v2 remain canonical; GX tasks are side-track links only.
2. The v3 JSON files are **handoff snapshots**, not updates to the GitHub repository. Before applying anything, fetch live repo JSON at the exact current HEAD and perform a three-way merge; never overwrite newer changes, blockers, evidence or append-only history from live JSON.
3. For an original task, `IN_PROGRESS` is a workstream acknowledgment, and `HOSTED_VERIFIED` requires named exact-SHA hosted evidence. Neither means `ACCEPTED`.
4. A case can switch from `NOT_RUN` only when its original required assertion is traced to named actual test IDs, results, source SHA, run/attempt, environment, and artifacts. Retain its evidence and negative/skip results. A test with no source-bound receipt must not be marked PASS.
5. A task's `implementation_sha` must identify the introducing commit, not merely a later passing cumulative HEAD. In this snapshot, `observed_cumulative_sha` is kept separately to avoid fabricating the introducing commit.
6. Preserve the 26 audit findings and old Phase31 evidence as history; migrate claims only after revalidation; do not import a stale PASS from legacy tests.
7. A green R09F Go/JVM/API36 run does not prove hardware root effects, complete R10 journeys, the five QS tiles, performance/battery benefit, or an approved release.
8. R10A stack integration belongs to GX06. No remote R10A PR had been confirmed at the snapshot date. Keep original R10 intact.
9. Mandatory original phase gates remain independent. No automatic R11 physical testing, R13 release signing, PR merge or rollout.
10. GX01's first output must reconcile exact GitHub source to this snapshot, generate an open-case inventory and separate evidence tiers, and run a full read-only validation before any write to repo.

## Evidence record minimum

`{ original_task_or_case_id, introduced_sha?, observed_sha, branch, PR, run_id, run_attempt, workflow, job, named_test_ids, assertion_mapping, artifact_name, artifact_sha256?, environment, result, exclusions, reviewer }`.

An unavailable field must be `null`/`UNVERIFIED`, never guessed. Historical artifact metadata alone is not an independently downloaded-byte digest.
