# R11A — Physical readiness, historical provenance, original-case reconciliation

This is an offline readiness batch. It does not satisfy R11, authorize installation,
perform device access, accept original test cases, or qualify a release. PR #42's HF02
head `931548d8cf74c4621e37f8f331462a0ddde5251b` has three successful hosted workflows.
The merge `74dfdfcc060d4677261cbc572057b173b28cdc08` has the same Git tree
`6bcc90b26646aced8bf2ccba04d9db0752df3f2a`. The historical baseline records observed
GitHub run and artifact metadata; the downloaded archive bytes are still pending.
Original ledgers, 178 tasks, 131 cases and 26 findings are untouched. The sidecar
maps all R08/R10/R11 cases to their unchanged original assertions. The three EM-019–021
candidate observations are NOT complete original assertion reviews.

## Offline validation (no device access)

Run `python3 -B scripts/ci/r11a_readiness.py` from the R11A checkout. This verifies the
pinned historical metadata and all mapped original assertions. To inspect the three
historical artifacts, obtain the original GitHub ZIP downloads (not `gh run download`
extracted directories). Use the exact artifact IDs and provider digest metadata in
`XBS_R11A_HOSTED_BASELINE.json` from PR #42. Do not download from an untrusted mirror.

    python3 -B scripts/ci/r11a_readiness.py --module-artifact module.zip --api36-artifact api36.zip --debug-artifact debug.zip

The verifier hashes complete outer bytes before bounded in-memory archive parsing;
checks inner checksums, the hosted receipt and app/test package, size and signer
metadata. It never extracts, installs or executes. This proves historical byte
consistency, not signer trust, release qualification or eligibility of a later commit.
The normal debug APK and emulator test build are deliberately separate. Before any
physical operation, verify the actual candidate with the existing R04 native module
ZIP validation, independently run `apksigner verify --print-certs` and `aapt dump
badging`, and obtain fresh exact-source hosted evidence. Historical artifact bytes
cannot be substituted for a fresh R11A build.

## Consent and read-only device inventory

Use a dedicated, authorized disposable API36 ARM64 physical test device. Do not run
these procedures against a primary phone. Obtain separate explicit read-only consent
first. Manually record API (`getprop ro.build.version.sdk`), ABI (`getprop ro.product.cpu.abi`),
emulator flag, root-manager package/version and signing certificate SHA-256 (inspect
its actual APK offline using Android SDK build tools), boot ID hash, installed module
ID/version/versionCode and SHA-256 of its `module.prop` and runtime manifest, installed
companion APK and signer digests, and a hash of the reviewed read-only state snapshot.
Only the operator may collect approved root-owned metadata using an independently
reviewed read-only procedure; no commands in this overlay invoke root. Never collect
IMEI, serial, account data, raw boot ID, arbitrary settings dumps or credentials.
Record only the exact keys in `INVENTORY_KEYS` and schema `xbs-r11a-inventory/v1`.
Include `module_present` and `companion_present` booleans. For an absent item set all
its identity fields to JSON null; for a present module include `runtime_sha256` with
the three actual installed files `xbs`, `XBSctl`, `XtremeBSd`. For a present companion
record the independently verified normal debug APK bytes and certificate digest.
`device_is_disposable`, `read_only_consent`, and `is_emulator` must be booleans.
The inventory is a supplied observation, not authenticated proof; verify the source,
signer, manager and actual installed runtime files independently. Unknown managers,
non-API36/ARM64 devices and malformed observations are refused. A valid older or absent
installation is reported as nonmatching and may only enter RD-001/RD-002 planning.
No mutation is needed to inspect.

Add `--verify-local-tools --inventory inventory.json` to the three-artifact invocation
from the reviewed R11A checkout to validate a previously captured **historical**
inventory; append `--plan-case RD-001` to render an individual read-only plan.
The tool runs the existing R04 native ZIP verifier and SDK build-tools 36.0.0
`apksigner` and `aapt` on private temporary copies. Supply `ANDROID_HOME` or
`ANDROID_SDK_ROOT`. Use real regular paths without symbolic-link components (copy
from Android shared storage aliases into a private directory; outer hashes must match). This cannot authorize or execute effects.
For the next live candidate use independently collected fresh-source evidence before
any effect; this historical verifier intentionally refuses source substitution.

## Physical gates

- RD-001: read-only observations first. Do not infer device acceptance from an offline
  JSON file. An independent reviewer must verify observed identity and provenance.
- RD-002 through RD-011 and RD-013: separate approvals for each specific case, device,
  exact artifact and before-state. Review maximum durations and restoration order.
  Never automatically install, reboot, kill processes, or change settings. Keep the
  previous signed/verified module and config backups off-device, plus independent
  root recovery access before any action. Stop immediately on drift or failed cleanup.
  Each result requires UTC start/end, actual assertions, before/after/restored digests,
  ownership of effects, cleanup verification and an independent evidence review.
- RD-012 (CPU/GMS/Wi-Fi disruption) is excluded from ordinary readiness; separate
  disposable hardware and explicit approval are mandatory. PF-008 (battery impact)
  requires an independently controlled physical study with confounders and actual data.
- RD-009/010 must never affect an unrelated process; no automatic daemon/phone reboot.
  RD-013 must demonstrate user configuration preservation, verified restoration and
  safe uninstall/upgrade. No readiness receipt asserts any such action occurred.

The retained original R11 acceptance gate remains pending. No source or ordinary CI
job performs physical effects. Signing, OEM/TalkBack, actual Tasker distribution,
full original assertion mapping, and physical performance remain separately open.

## Fresh R11A candidate (required before effect planning)

On the exact reviewed clean R11A checkout (or its identical-tree merge), use the
successful R11A PR head SHA and its three actual workflow run IDs:

    python3 -B scripts/ci/r11a_readiness.py --candidate-source PR_HEAD_SHA --run RUN_ID_1 --run RUN_ID_2 --run RUN_ID_3

This requires authenticated `gh` and performs read-only GET requests against the
canonical GitHub repository. It checks exact local ancestry, published Git tree,
PR run event/head/repository, current run attempts, every mandatory job, and all
seven artifact metadata identities. A stale/foreign/failed/incomplete run is
refused. Download the original three artifact archives identified in the output;
then repeat with the three artifact paths and, only after independently capturing
actual device observations, `--verify-local-tools --inventory inventory.json --plan-case RD-001`.
For R11A use these fresh bytes rather than the historical PR42 files. An expired
artifact, changed commit or unavailable GitHub read prevents planning. An effect
plan other than read-only inventory requires the live-provider path. Even this
plan is non-executable and cannot establish physical acceptance or authorization.

### Termux traversal regression (R11A-F01)

The offline verifier uses search-only directory descriptors (`O_PATH` on Linux/Android, `O_SEARCH` where provided), retaining `O_DIRECTORY` and `O_NOFOLLOW` on every ancestor. It does not require directory listing permissions. The terminal file remains a bounded regular-file read. The regression fixture runs with an execute-only ancestor under the actual unprivileged Android UID or a Linux qualification child stripped of all capabilities when the runner is root; both ancestor and terminal symlink controls must still fail. The original R11A native precommit validation failed and rolled back; F01 is a consolidated replacement from the same merged preimage, not a patch to an applied R11A commit.
