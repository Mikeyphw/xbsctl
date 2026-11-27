# XBS aggregate integration qualification — 18 November 2026

Immutable target source: `Mikeyphw/xbsctl` PR #35 `77825deb673c4973e82b9c428efcbc5991409a11`; tree `b12b0809438cf56b94c81d4f49c2f3c849cf3b9e`.
Current main at inspection: `c52561dfa203090befb680a058aa5e5e79870f1b`. Reviewed read-only `git merge-tree --write-tree` output: `ae7292929149833cbecd65667d9a4f7b0588697d`, conflict-free.

This source-bound **native Devtool overlay** creates one named candidate stacked on PR #35, not on main. Five S7 P05 source files are imported from **exact** main using verified Git blob SHA and retained byte-for-byte. The already-resolved `android-app/build.gradle` remains unchanged with source blob `3c0fbf076bef2868f521bd1c052397a9fde187cc`.

Qualification-only changes to existing source auditors allow exactly one new direct descendant of PR #35, with the complete new-file inventory and parent SHA pinned. No security gate, physical qualification, original-case count, or signing policy is relaxed. Canonical Go/JVM/API26/API34/API36 **and** S7 P05 Android consumer workflow ID `418896455` are required at the actual aggregate HEAD. Original 178 tasks, 131 cases and 26 findings remain unaccepted where outstanding.

The aggregate HEAD intentionally has additional qualification-only files beyond the raw merge preview tree. The five S7 main blob identities plus unchanged PR35 Gradle blob, exact PR35 parent and exact changed file inventory prove the combined source content; `aggregate_ci_qualified` remains false until hosted runs succeed. The preflight recomputes and checks the exact `ae7292929149833cbecd65667d9a4f7b0588697d` merge-tree. Native full hook runs GX04, GX06, R08/R10, S7 source tests where supported, and new aggregate negative tests; Gradle is hosted-only. Native precommit Python imports from the repository-root `scripts.ci` namespace and hashes Git objects via `bytes([0])` (no raw NUL in hook). The package self-test exercises the exact PREGATE block positively and with a tampered fixture blob.

**Acceptance:** check current main and PR #35 SHA, verify the read-only merge preview and artifact inventory; apply with latest source-bound Devtool Launcher 3.1+; inspect the native durable session and all three workflow IDs on the exact resulting commit. Do not merge into `main` on the basis of a green side-branch CI without aggregate acceptance. PR #34 is closed. No physical-device/root actions or release/signing requested.

## R07A aggregated CI continuation (exact source-bound)

On PR #36 at `b693612fc76bef3bd4d7fb8375861a990180fd0a`, canonical Go
CI refused the independently merged S7 P05 workflow as UNREVIEWED_EXTRA_WORKFLOW;
hosted JVM full-Python CI failed the corresponding R07A policy tests.
The continuation permits only the original two workflows or those plus the exact
S7 P05 Git blob `11367960a6fd311926db890b632433000b767a0b`; unknown,
symlinked or modified workflow files continue to be refused. The source
ancestry gates check direct parent PR #36 and this exact 9-file delta.
All three required aggregate workflows remain mandatory on the new PR head.
No branch rewrite, main merge, physical qualification, signing or release.

## AGG-R07A-HF01 — source-history gate correction

PR #37 source `5cfa4e6e846263d85338b5d394e201fd6ac4f6c1` failed
[canonical Go run 44411366017](https://github.com/Mikeyphw/xbsctl/actions/runs/44411366017)
with `R08R10_CHANGED_PATHS_DRIFT`: the R08/R10 verifier selected HEAD for the
aggregate repair rather than the immutable R08/R10 endpoint. GX03 duplicated
the same selection. A fresh commit on the existing PR also needs a reviewed
parent and delta; simply correcting that conditional would reject the new HEAD.

All four consumers now use one linear history verifier with exact single-parent
checks, pinned historical endpoints, NUL-delimited exact changed paths (no rename
heuristics), and an explicit eleven-file HF01 delta on the original PR #37 head.
The existing PR branch and base are unchanged. The R08/R10 evidence gate now accepts that exact aggregate base only with the unchanged three-workflow inventory; local source audit exposed the previously unreachable base refusal. Real disposable Git-history tests
exercise every terminal stage, historical boundary selection, extra/missing paths,
wrong pins and parents, and merge-commit rejection; the previously undiscovered
GX06 integration tests now run in the mandatory direct-script suite. All three
workflow IDs, six protected integration blobs, original 178 tasks / 131 cases /
26 findings and separate physical/signing/release boundaries remain unchanged.
Hosted acceptance requires fresh exact-source runs; no green result is presumed.

Root pytest discovery explicitly excludes only the retired `archive/legacy-suite-tests`
collection (documented in `archive/README.md`); no active CI or S7 tests are excluded.
