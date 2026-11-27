# GX06 — exact-stack integration and original-roadmap handback

**Target:** `Mikeyphw/xbsctl`, `feature/xbs-gx06-stack-integration-20261118`,
stacked on GX05 PR #32 HEAD `1b5fbcd42bd39956c5251ea8f43eadbddf7aefcf`.
The target source and complete 10-file changed inventory are pinned in the
immutable native Devtool v3.1+ package at build time. No automatic merge,
release, physical root or user-device mutation is authorized.

## GX06-01 to -06 delivered source controls

1. **R10A naming:** R10A was a prepared side-track integration preflight; it
   is not an applied/qualified original R10 phase.
2. **Five-PR stack identity:** `XBS_GX06_STACK_LOCK.json` pins PRs #27, #28,
   #30, #31, #32; exact base branch/head, current SHA, required workflows and
   known historical successful run IDs. Source auditor checks direct GX05
   ancestry, exact 10-file delta, and immutable original ledgers.
3. **Live read-only GitHub qualification:** `gx06_live_audit.py` independently
   rechecks those PRs, exact run attempts/jobs, artifact metadata/digests and
   current source, and can verify the new GX06 PR's two required workflows.
   The reported GitHub digests are provider claims until actual downloaded
   bytes are independently rehashed; artifacts must never be asserted signed.
4. **Merge-readiness:** optional read-only `git merge-tree --write-tree` reports
   whether the exact current `main` and GX06 head conflict without applying a
   merge. Even a conflict-free preview does **not** qualify an aggregate or
   replace required fresh full CI on the intended aggregate SHA. Main contains
   separately merged S7 P05, which is **not** silently assumed included in
   this stacked PR lineage. Original R09F PR ancestry must be reviewed too.
5. **178/131/26 inventory:** current original ledger, test matrix, and GX side
   tracker are inspected for 178 unique original tasks, 131 unique cases, 26
   audit findings and six GX IDs. Immutable source at GX05 is checked for no
   changes. The partial historical GX01–GX05 hosted evidence does not promote
   even one original R08–R13 case without assertion/run/attempt proof.
6. **Original-roadmap resumption:** priority is R08/R10 missing Android and
   exact acceptance mapping, R11 consent-based physical device validation,
   R12 security/performance residuals, and R13 separately approved signing
   and release. Preserve original R00–R13 IDs and all 131 cases; do not
   renumber, erase findings, or auto-complete a gate.

## Required GX06 CI

GX06 inserts no replacement workflow or optional bypass. Existing canonical
`go-contract`, JVM, API36 and API26/API34 jobs remain required. The existing
GX02 hosted source auditor runs GX06's additional read-only source audit on
GX06 descendants. Previous GX03/GX05 instrumentation and performance gates
remain active and must execute on the **new** GX06 HEAD.

The committed `config/delivery-required-ci.json` is correctly rebased from
GX04 to GX05; the native Devtool delivery policy is checked **after** the
single atomic commit when resuming the durable session. First apply does not
ask Devtool to watch an obsolete base-branch CI policy. Native Devtool owns
preflight, scaffold, overlay validation, apply/commit, durable session,
Git SSH push, PR and CI. No local Android Gradle on Termux.

## Evidence and refusal boundaries

- GX01–GX05 recorded hosted runs were green when observed; live PR and
  artifact availability must be rechecked.
- GX06 cannot claim hosted completion until the new exact-HEAD Go/JVM/API36
  and API26/API34 workflows pass plus exact event/run/attempt evidence.
- `XBS_GX06_COVERAGE.json` deliberately retains `original_cases_accepted=false`,
  `physical_root_qualified=false`, `signed_release_qualified=false`,
  `battery_savings_measured=false`, `auto_merge_approved=false`.
- Real ANR/StrictMode soak, wakeup/polling, physical ARM64 root/OEM behavior,
  destructive CPU/GMS/Wi-Fi, Restore Defaults daemon support and signed
  release remain explicit blockers. Source-only and rootless emulator results
  are **not** approvals for these actions.

## After native delivery and hosted CI

From the GX06 branch's clean worktree (not the user's primary checkout), run:

```bash
python3 -B scripts/ci/gx06_live_audit.py --repo . --current-gx06 \
  --output "$HOME/.local/state/xbsctl-gx06/live-stack-audit.json"
```

A new output file is created exclusively (0600) and never overwritten.
For an optional exact-main conflict preview, first make sure the **current
main SHA object** is present locally using a separate read-only approved fetch;
then add `--merge-preview`. This does not merge.

**Final status at packaging:** source implementation ready; native Devtool
validation, hosted GX06 CI, live PR evidence, aggregate CI, physical and
release acceptance NOT YET PERFORMED.
