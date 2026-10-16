# Phase 32B r4 Gradle TestKit lifecycle hotfix

Phase 32B r2 and r3 both preserved the runtime bridge parser fix but still failed during
`:phase31-testkit:test` when the TestKit fixture executed `phase31AssertGraph` under
Gradle 9.7.1.

The failure was not caused by Android code. It was caused by the release-seal Gradle
script consulting Gradle runtime dependency APIs during lifecycle phases where TestKit may
be transitioning the build state:

- r2 resolved task dependencies in `gradle.projectsEvaluated`.
- r3 avoided those APIs, but read `ext.phase31GraphSnapshot` from the task extra
  properties extension inside `doLast`, so the project-level snapshot was invisible and
  Gradle raised `MissingPropertyException`.

r4 fixes the contract by keeping the graph declaration snapshot in a lexical local variable
captured by the assertion task closure and mirroring it to `ext` only for diagnostics. The
assertion no longer calls `taskDependencies.getDependencies(...)`, no longer calls
`mustRunAfter.getDependencies(...)`, and no longer reads the snapshot from a task-local
extra property.

Android/Gradle/device/signing/profile/final-seal gates still need the real Devtool run.
