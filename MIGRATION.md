# XBS split from mike-cli-suite

Go module identity is now `github.com/Mikeyphw/xbsctl` (previously `github.com/mikeceead/xbs`).

XBS source was moved from `apps/xbs/` to the root of this repository.
- `cmd/`, `internal/`, `module/`, `system/`, `android-app/` and Go tests are the active code.
- `archive/` is historical reference only, not part of the release.
- Existing `xbsctl` Python/Textual sources are intentionally retired; original Git commits are preserved locally under a `backup/pre-xbs-migration-*` branch only. The new GitHub `main` starts at a single **new root commit**, with no Python history.
- `mike-cli-suite` drops XBS build, install, Android targets, dedicated tests and hosted CI. Shared server-manager compatibility for externally installed XBS may remain, without an embedded runtime.
- No remote branch force pushes, module installs, signing, or device testing happen during migration.
