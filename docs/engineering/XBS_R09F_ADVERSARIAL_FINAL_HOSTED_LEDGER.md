# XBS R09F — deterministic adversarial schedules and final hosted evidence ledger

Parent: `feature/xbs-r09e-seven-package-boundaries-20261114`, exact pinned SHA `9f6e58473fcad26b26ca07eb1a7ec6927fde63b0` (R09E Go/JVM/API36 SUCCESS, run 43648376860).

## Scope of this overlay

* Tuning: four deterministic seeded permutations of six **post-effect** crash points on an entirely in-memory Android model; fresh engines recover from exact preimage snapshots. Twelve journal-release failure/retry cases and twelve pre-existing external-state ownership cases. A simulated panic is an in-memory test and is **not** proof of real process death on an Android device.
* Control: sixteen claim→crash/ack→restart scenarios, sixteen immutable competing-completion scenarios and eight symlink-parent refusal scenarios, each using an isolated temporary spool.
* Hosted CI runs six named Go root tests (88 named subcases), three repetitions (18 roots, 264 subcases) and the Go race detector (6 roots, 88 subcases). It refuses unexpected test IDs, duplicates, skips, failures, run/source drift, package drift and missing subcase events.
* The source/run/attempt-bound JSON ledger verifies all five preceding A–E phase receipts, including their exact SHA-256 and sizes. It independently revalidates the R09D full coverage profile and R09E coverage binding. Full internal-package coverage must not fall below **72.07%**, the observed R09E run #43648376860 baseline. Every one of the seven risk packages must satisfy the existing 50% floor.
* The independent R09F GitHub auditor verifies exact PR head/base, latest matching completed `pull_request` run, required Go/JVM/API36 jobs, all A–F Go steps, exact stdout marker and downloaded artifacts and their source-bound payload bytes.

## Qualification boundaries

Hosted success is **not** physical root, real Android power/service mutation, upgrade/install, signing or production release signoff. Two expected root-only skips in the full-suite coverage lane remain explicitly recorded. Optional Android debug may remain skipped. There is no local Gradle and no device write.

## Next roadmap / remaining gates

1. Obtain an exact SHA/attempt, green R09F hosted Go/JVM/API36 workflow with independently validated final A–F receipt chain and risk coverage ledger.
2. Review cumulative A–F stacked PR ancestry, review status, merging strategy, and default-branch integration without flattening or silently claiming approval.
3. Separately prepare **explicitly authorized** disposable-device/root acceptance: source-bound binary, read-only preflight, isolated reversible effects, posttest restoration with precise before/after state, recovery under interruption, and artifact hashes. Stop if safety or restoration cannot be proven.
4. Release readiness: signing/provenance policy, final full CI on the intended release commit, manual device acceptance and operator signoff. No automatic release on R09F success.

## Recovery if hosted CI fails

Use `gh run view <id> --log-failed` and the structured job logs. Fix on the exact R09F branch as a source-pinned consolidated hotfix. Never force push, overwrite user changes, or mark a test skipped as green. The Python/Rich ZIP supports read-only `--check`, isolated first-run PR creation and resumable exact-run discovery.
