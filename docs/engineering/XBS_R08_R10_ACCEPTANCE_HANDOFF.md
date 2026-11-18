# R08/R10 source-anchored follow-on acceptance and S7 P05 merge compatibility

**Parent source:** GX06 `5287f4a41eedc2b7fa3a33c6af820f117db78b84`.
**Delivery:** separate named R08/R10 feature branch using native Devtool v3.1+.
**No direct merge, no physical device/root writes, no release, no signing.**

## Consolidated scope

- Preserve the exact standalone Devtool manifest path from S7 P05 on main:
  `def devtool = rootProject.file('../.devtool.toml').text`.
  Move the GX06 missing-manifest refusal to the beginning of the same Gradle
  task, ahead of any source reads, so the S7 P05 one-line edit and the GX06
  additional guard are separate three-way merge hunks.
- Extend *real, rootless hosted API36* Gx04 instrumented tests with six
  substantive Quick Settings safety and lifecycle assertions, including
  full state-refusal matrix, event configuration, outstanding recovery,
  restore-defaults non-dispatch, configuration Activity recreation, and
  callback evidence never granting privileged action authority.
- The canonical API36 hosted orchestrator requires every new method in its
  actual AndroidJUnit XML receipt. The existing API26/API34 jobs stay required.
- Enforce source guards and negative regression tests in the canonical Go job,
  not just an offline launcher pass. Preserve exact commit and run/attempt
  evidence and verify test counts on GitHub.
- Consolidate the direct GX06 descendant boundary into existing GX02/GX03
  source auditors and GX06 stack gate. They continue validating the immutable
  original GX05->GX06 inventory and exact source ancestry, and refuse
  unrelated or unknown new changed-file inventories.

## Acceptance boundaries

The six methods are newly required hosted execution checks. They are **not**
proof that original R08 or EM-019..021 acceptance is complete. Source-only
method presence and rootless API36 Android assertions cannot prove physical
OEM/lockscreen behavior, Android kernel/root effects, or real battery savings.
The original 178 tasks, 131 cases and 26 findings remain unchanged, and the
unaccepted R08/EM cases remain explicitly open until independently mapped to
all original assertions with run/attempt/artifact bytes and relevant devices.

The single overlapping `android-app/build.gradle` conflict is deliberately
addressed without a merge. A new exact-HEAD `git merge-tree --write-tree`
against the current main still must return conflict-free; a preview is not an
aggregate commit or hosted-aggregate CI. If main moved, re-audit; never silently
repin an immutable native package, force-push, merge or rebase user work.

## Validation and evidence

Native Devtool full executable hook runs the existing GX06 source regression
suite, the new negative source gates, and checksummed changed-file assertions.
Hosted evidence must include: Go/JVM/API36 with all six methods executed,
API26/API34 compatibility, correct PR HEAD, run IDs/attempts, JUnit actual
counts, and independently rehashed downloaded evidence if cryptographic
qualification is claimed. A GitHub provider digest alone is not independent
file verification. No Gradle on Termux.

**Outstanding:** exact main merge preview, true aggregate CI, remaining R08/R10
original assertions, R11 physical consent/validation, R12 full performance,
R13 signing/release and human merge approval.

## Consolidated rebuild: hosted regression failure fixed

The prior draft PR #34 at `17bdfa3378af19f82118b8fb61ab7202c25c6768`
failed hosted JVM Python preflight: `test_gx04_tiles.py` expected nine
instrumentation tests, but the new Android class deliberately contains fifteen.
The original GX04 test is now **updated, not skipped or removed**: its existing
`test_24` verifies all 15 unique Java `@Test` methods against all 15 actual
`junit_receipt` expected selectors in the mandatory API36 host. It refuses
missing, duplicate and unreviewed tests. New negative tests check loss of
historical requirements.

Native full executable validation now runs `test_gx04_tiles.py` itself in
addition to the original GX06 regressions and the consolidated R08/R10 source
regressions. A local Java-policy test may skip only if Java is unavailable;
such a skip does **not** qualify hosted JVM or API36 evidence.

This rebuilt input package creates a **new isolated named branch** from the
original GX06 SHA. Draft PR #34 is preserved as failed historical evidence
and must be closed separately after the replacement is independently green.
Reusing its branch would require prohibited history rewriting. Fresh native
Devtool commit/PR, hosted Go/JVM/API26/34/36 tests, exact-main conflict preview
and aggregate CI remain pending; no device/root/signing/release acceptance.
