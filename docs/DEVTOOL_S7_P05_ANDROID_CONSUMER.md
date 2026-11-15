# S7 P05 — XBS Android consumer adoption, source-bound hosted debug lane

The original Devtool S0–S7 roadmap migrates only orchestration that belongs in
Devtool. The `Mikeyphw/xbsctl` consumer already has canonical `devtoolw`, a
`.devtool.toml` configuration, and the `xbs_android` target. P05 leaves those
intact and adds a PR-triggered hosted Android check plus source-bound adoption
contracts. The earlier `devtool-xbs-ci.yml` Go/module candidate and manually
opted-in Android debug path remain unchanged. Existing XBS Rxx/GXxx PRs are not
rewritten, rebased, closed, or merged.

## Source and workflow boundaries

- Initial consumer base is `f048d2d5526a3d0d7632e477b6b4f052e41e30df`
  from `Mikeyphw/xbsctl/main`. The P05 launcher refuses HEAD/tree/known blob
  drift and a dirty worktree. New files are created by canonical
  `delivery adoption scaffold`, then `validate overlay` and `apply overlay`
  with `--target xbs_android` and the exact-tree source contract.
- The dedicated PR workflow checks out the exact PR head SHA, installs pinned
  canonical Devtool S7 P02 at `416d24140c7f7a4b9833d7cb19df50ca7e7363b8`
  and runs its real read-only audit and exact-tree drift-negative test.
- Only hosted GitHub Actions runs Gradle: `:app:assembleDebug` and
  `:app:testDebugUnitTest` on JDK 21, Gradle 9.7.1, compile SDK 36. It verifies
  the debug APK, writes its SHA256 and the exact source SHA, then retains
  non-release artifacts. Local Termux launcher never installs Android SDK,
  runs Gradle, dispatches workflows, or writes to any Android device.
- S7 P05 does not claim Android API36 emulator UI coverage, physical-device
  instrumentation, signing, release, module installation or merge readiness.
- A GitHub-assigned P05 workflow ID and exact successful run/attempt are needed
  before creating a required-CI policy. Never invent an ID or treat historical
  runs from other source commits as proof.

## After installation

Use the launcher `--ci-only --resume-id` mode for the new durable session;
it observes the exact PR without replaying push/PR creation. The existing
`gh pr checks` output is advisory until the Devtool registrar can audit an
explicit pinned CI policy in a later acceptance slice.
