# GX02 — Devtool, CI and delivery reliability: implementation handoff

**Lane**: Python/GitHub-hosted control-plane (not Android feature, physical-root, signing or release).

## Source and historical evidence

- Baseline PR: #27, `feature/xbs-gx01-evidence-reconciliation-20261114`, head `568ba5dd140164bf179b91dd24c2557f114f0aa6`; parent PR #26 `e8647e8f80833d26fe6a6164755ae493f4a37301`; `main` `f048d2d5526a3d0d7632e477b6b4f052e41e30df` at inspection.
- Confirmed GX01 hosted run `43662889628` concluded success; three required Go/JVM/API36 jobs succeeded, optional debug skipped. Exact `run_attempt` requires live REST confirmation (not inferred from run ID). Do not relabel GX01 static local receipt; it was accurate for its creation date.
- Devtool previously source-pinned by existing R06B hosted CI at `44eb17bbbd63194aa67cde0f7b63d5ada44fb96e` (1.1.0). The new GX02 default launcher insists that Devtool exists in Termux, uses its native artifact-format v2 apply transaction and never attempts Android Gradle locally.

## Actual GX02 source additions

- `scripts/ci/gx02_control.py`: pure policy library for run selector/attempt state, PR reuse, 0600 fsynced journal, ZIP safety/sha, module/APK/JVM/test/coverage identity checks and workflow insertion; no network or write primitives beyond explicit Journal and native overlay creation.
- `scripts/ci/gx02_ci_verify.py`: independent hosted guard after R06B native affected-target execution; verifies unchanged 178 original tasks, 131 cases, 26 findings, immutable GX01 files, exact checkout/parent, canonical R07A workflow, source/attempt-bound real Go cases, R06B Devtool source+wheel and all three affected scenarios, R09F receipt and risk coverprofile. It rejects zero/failed cases, unsanctioned skips, changed original ledgers, omitted hosted gates, and any claimed physical/root or release qualification.
- `scripts/ci/test_gx02_control.py`: 75 distinct offline tests of controls and adversarial cases; run unconditionally inside the original hosted Go job, not as an optional check.
- `.github/workflows/devtool-xbs-ci.yml`: **one** new GX02 step in existing mandatory `go-contract` job after R06B/GX01, preserving canonical Go/JVM/API36 job names and their `needs`, immutable action pins, least-privilege token and source checkout expressions.
- `docs/engineering/XBS_GX02_SOURCE_PINS.json`, `XBS_GX02_LOCAL_STATUS.json` and this handoff: append-only GX evidence without mutating GX01's original ledger/matrix or task criteria.

## Executable exit gate

1. Live GX01 PR #27 and run #43662889628 still match exact ref/commit/workflow, and main source pin is unchanged. Source drift **refuses** creation rather than rebasing silently.
2. Disposable Git worktree is clean; `devtool status`, `overlay plan`, `overlay check`, `validate-overlay`, and `apply-overlay --no-validate --no-commit` accept the schema-v2 inventory. Devtool itself owns atomic changes and recoverability.
3. Source-pinned original files and correct workflow insertion; 75 GX02 and inherited 32 GX01 negatives plus existing R06B/R07A static validations pass. Only then commit/push; no force push, automatic PR duplication, merge or dispatch.
4. New exact-SHA GitHub run has required three jobs success and *actual* GX01+GX02 named successful steps; optional debug may skip. R06B native affected-target checks must remain executed; independent on-run audit must pass.
5. Three run/attempt artifact payloads observed and downloaded, with exact executable test counts and Go/JVM/APK/module checksums verified. Never upgrade a GitHub-reported ZIP digest into a locally rehashed ZIP digest without actual ZIP bytes.
6. Journal records `HOSTED_VERIFIED` only after all above; pending returns remain pending and may resume.

## Negative and future scope

The GX02 runner refuses foreign repository/workflow/event/branch SHA, stale run time or attempt, duplicated PR/run/job/step, failed required gate, missing artifacts, bad digest/coverage, symlink/traversal, modified original evidence, corrupt journal, and unapproved device commands. It does not qualify original R08 Quick Settings, original R10 journeys, ARM64 physical root, R11 physical acceptance, R12 performance savings, or R13 signed release.

## Continue

**GX03** (one consolidated hosted emulator journey/API26/API34/API36 overlay); **GX04** (one consolidated native QS TileService overlay); **GX05** (one consolidated security/performance/read-only physical-readiness overlay); **GX06** (one consolidated stack integration and original-roadmap handback). Original **R00–R13 / 178 tasks / 131 cases / 26 findings** archived byte-for-byte inside prior `docs/engineering/gx01_handoff/` and the bundled continuation handoff. Original case acceptance remains OPEN/UNMAPPED until assertion-level evidence exists.

## HF01 launcher interface correction

The Devtool preflight is invoked without machine-format flags, and its standard readable output is shown in Rich. The pure control plane rejects the forbidden flag before executing any command. This is a launcher-interface correction only, not new Android implementation or acceptance proof. The GX02 negative suite is now 75 tests.


### HF02 launcher repair (source payload unchanged)

Live GitHub check on 2026-11-15 UTC: GX01 PR #27 remains open at
`568ba5dd140164bf179b91dd24c2557f114f0aa6`, with run
`43662889628` previously successful; no GX02 PR or branch was returned.
The user's `EXISTING_GX02_JOURNAL_REQUIRES_RESUME` identifies a local,
validating journal guard, not a remote failure. HF02 auto-resumes verified
existing journals by default; `--journal-status` is read-only, and an owner-only
exclusive flock prevents parallel attempts. Never destroy a journal to bypass
recovery. Eight extra launcher-level tests run alongside the same 75 GX02 native
payload tests. No new remote CI acceptance is claimed here.

## HF03 — native Devtool repository identity and actionable diagnostics

**Reproduced cause:** the pinned Devtool `devtool/artifacts.py::_validate_repository_identity`
checks manifest `repository.name` against the basename or full path of the
current repository. HF02 emitted the original repository name `xbsctl`, but
its disposable worktree's actual basename was `xbs-gx02-rirx5xa4`. Devtool
correctly refused compatibility; the local delivery journal was not corrupt.

HF03 generates the schema-v2 manifest with `repository.name` equal to the
*existing journaled disposable worktree basename*, and requires the exact Git
remote and GX02 branch using `enforce_remote=true` and `enforce_branch=true`.
The clean-worktree requirement, per-file source/payload SHA-256, atomic apply
and no local Gradle remain mandatory. Source pins and the GX01 handoff are
unchanged. No bypass/force/ignore-checksum option is used.

Preflight now runs native manifest validation and overlay check **before** the
summary-only overlay plan, printing up to 6,000 characters of the direct
human-readable output from a failed command, with its exact stage and exit
code. No `--json` option is passed. HF03 adds seven tests: 75 existing hosted
payload tests + 8 HF02 journal tests + 7 HF03 identity tests = 90 offline PASS.
The actual pinned Devtool transaction has not been executed in this container,
and hosted GX02 remains NOT_EXECUTED. Existing WORKTREE journal at
`~/.local/share/xbsctl-gx02/delivery.json` can be resumed by a normal run;
**never delete or reset it**.
