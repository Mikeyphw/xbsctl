# S7 P05 HF02 — correct Android Gradle repository-relative Devtool path

**Repository:** `Mikeyphw/xbsctl`  
**Existing PR:** [#29](https://github.com/Mikeyphw/xbsctl/pull/29)  
**Required source commit:** `2744020da986fd7334592a0a7692a8d48d57674f`  
**Failure:** [P05 hosted Android build #43682303412](https://github.com/Mikeyphw/xbsctl/actions/runs/43682303412)

HF01 fixed the obsolete Android SDK `tools` bootstrap; the new hosted run reached Gradle
9.7.1 and failed on `:verifyStableToolchainBaseline` because `android-app/build.gradle`
used `rootProject.file('../../../.devtool.toml')`. With the Gradle root set to
`android-app`, that resolves above the repository into `/home/runner/work/.devtool.toml`.

HF02 changes exactly one Groovy expression to `rootProject.file('../.devtool.toml')`,
which resolves to the tracked `.devtool.toml` in the repository root. Two Python
unittest regression cases assert that the relative path resolves to the repository
configuration both in-place and under a relocated checkout directory.

The existing S7 P05 hosted Android workflow and its SDK v4 fix are **unchanged**.
The app's Android product behavior, AGP 9.3.1, Gradle 9.7.1, API 36, tests,
APK signature and SHA evidence are preserved. No local Gradle or device writes.
No release, merge, signing or physical-device acceptance is implied by this fix.

The initial `--check` is read-only; `--apply --approve-remote` invokes canonical
Devtool scaffold, validate, transactional apply/commit, session push, PR reconcile,
then direct `gh pr checks`. No force push or automatic merge.
