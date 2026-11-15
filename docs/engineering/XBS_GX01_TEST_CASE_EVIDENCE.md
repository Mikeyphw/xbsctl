# GX01 — original 131 test cases: assertion-level inventory

**Important:** successful hosted workflow jobs do not establish the whole original assertions. All 131 remain **UNMAPPED/NOT_RUN** at case level.

Verified R09F Go method inventory: six named roots / 88 subtests; 47 source-pinned methods across jobs; 46 partial candidate references for 33 cases.
Method source traces are tied to PR #26 exact SHA, green Go/JVM/API36 jobs and run 43650947189 attempt 1; only R09F has independent named root/subtest count. Individual other-method XML was not inspected, and original cases are not marked PASS.

| Case | Required assertion | Lane | Case status | Named candidate methods (partial only) |
|---|---|---|---|---|
| AJ-001 | Rejected before root shell execution | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| AJ-002 | Atomic old/new result or recovery-required | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| AJ-003 | Configuration never marked fully committed | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| AJ-004 | Owned command not blindly rerun | ANDROID_JVM_HOSTED | NOT_RUN | ownedPendingCommandSurvivesMemoryLossWithoutReplay, persistedPendingCommandSurvivesInMemoryProcessStateLoss |
| AJ-005 | Only matching owned process targeted | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| AJ-006 | Truthful failed and cancellation state | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| AJ-007 | Unauthorized config rejected | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| AJ-008 | Strict refusal and no duplicate action | ANDROID_JVM_HOSTED | NOT_RUN | unsignedActionFailsWithoutStartingRoot, policyDetectsModifiedSignedPayload |
| AJ-009 | Platform-specific trust rules enforced | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| AJ-010 | Tasker only gets RESULT_OK for observed applied state | ANDROID_JVM_HOSTED | NOT_RUN | unsignedConditionIsUnknownRatherThanSatisfied |
| AJ-011 | Authenticated, fallback, blocked statuses correct | ANDROID_JVM_HOSTED | NOT_RUN | forgedAndMalformedBridgeEvidenceNeverBecomesAuthenticated |
| AJ-012 | Fields blocked with visible message | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| AJ-013 | No unapproved critical package change | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| AJ-014 | No data loss, stable indexes and cleanup | ANDROID_JVM_HOSTED | NOT_RUN | serviceHistoryPathsDoNotOpenRoomSynchronouslyOnMainThread |
| AJ-015 | Every view gets consistent user-facing state | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| AJ-016 | No sensitive value in report | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| AJ-017 | Single desired state transition and correct last status | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| AJ-018 | Proper tile state/subtitle and no false applied | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| AJ-019 | Correct real variant and count, no alias fake pass | ANDROID_JVM_HOSTED | NOT_RUN | UNMAPPED |
| BE-001 | Valid v2 defaults and expected paths only | GO_UNIT_HOSTED | NOT_RUN | UNMAPPED |
| BE-002 | No daemon mutation and stable machine-readable parse failure | GO_UNIT_HOSTED | NOT_RUN | TestR09CCLIGoldenConfigValidation |
| BE-003 | Fail closed with exact field error | GO_UNIT_HOSTED | NOT_RUN | TestR09EConfigBoundaryGuards |
| BE-004 | Reject without writing outside fixture | GO_UNIT_HOSTED | NOT_RUN | TestR09EConfigBoundaryGuards |
| BE-005 | Old or complete new configuration, never torn | GO_UNIT_HOSTED | NOT_RUN | UNMAPPED |
| BE-006 | Both refuse identically and preserve current state | GO_UNIT_HOSTED | NOT_RUN | UNMAPPED |
| BE-007 | Deterministic precedence and no missing mandatory keys | GO_UNIT_HOSTED | NOT_RUN | UNMAPPED |
| BE-008 | Exact effective delay/dry-run policy reported | GO_UNIT_HOSTED | NOT_RUN | UNMAPPED |
| BE-009 | No crash/traversal/invalid accepted input | GO_UNIT_HOSTED | NOT_RUN | TestR09EDaemonArgumentAndEventBoundaries |
| BE-010 | Exact documented exit code and machine JSON | GO_UNIT_HOSTED | NOT_RUN | TestR09CCLIGoldenUsageAndExitCodes, TestR09ECLIArgumentAndJSONRefusals |
| BE-011 | Stable schema fields and no ambiguous success | GO_UNIT_HOSTED | NOT_RUN | TestR09CCLIGoldenSuccess |
| BE-012 | Accurate completion and exit code | GO_UNIT_HOSTED | NOT_RUN | UNMAPPED |
| BE-013 | All unique IDs processed once in intended order | GO_UNIT_HOSTED | NOT_RUN | UNMAPPED |
| BE-014 | No duplicate effects and truthful recovery status | GO_UNIT_HOSTED | NOT_RUN | TestR09FQueueRestartSchedule, TestR09FAckImmutabilitySchedule, TestR09DQueueClaimAckCrashMatrix, TestR09DImmutableAckRejectsCompetingCompletion |
| BE-015 | Refused/quarantined and no path escape | GO_UNIT_HOSTED | NOT_RUN | TestR09FSymlinkPathRefusalSchedule, TestR09EControlAckIdentifierBoundary |
| BE-016 | Restores safely or reports recovery-required | GO_UNIT_HOSTED | NOT_RUN | UNMAPPED |
| BE-017 | Stops retain still-owned effects | GO_UNIT_HOSTED | NOT_RUN | UNMAPPED |
| BE-018 | Zero system writes and explicit simulated status | GO_UNIT_HOSTED | NOT_RUN | TestR09CFakeAndroidDryRunIsPure |
| BE-019 | No phantom active/healthy state | GO_UNIT_HOSTED | NOT_RUN | UNMAPPED |
| BE-020 | Zero data races and nonempty tests | GO_UNIT_HOSTED | NOT_RUN | TestR09FAckImmutabilitySchedule |
| BE-021 | Consistent outputs; flakes retain evidence | GO_UNIT_HOSTED | NOT_RUN | TestR09FAdversarialCrashSchedule |
| BE-022 | No panics, unsafe allocation or hangs | GO_UNIT_HOSTED | NOT_RUN | UNMAPPED |
| BR-001 | Authenticated payload with expected sequence and digest | GO_BRIDGE_HOSTED | NOT_RUN | UNMAPPED |
| BR-002 | Hard failure with non-authenticated status | GO_BRIDGE_HOSTED | NOT_RUN | UNMAPPED |
| BR-003 | Bounded refusal without panic | GO_BRIDGE_HOSTED | NOT_RUN | TestR09EBridgeNumericAndDigestBoundaries |
| BR-004 | Rejected or explicitly duplicate per contract | GO_BRIDGE_HOSTED | NOT_RUN | olderSequenceCannotOverwriteCommittedCursor, equivocationRefusedAndDuplicateIdempotent |
| BR-005 | Rejected with security reason, never fallback as verified | GO_BRIDGE_HOSTED | NOT_RUN | forgedAndMalformedBridgeEvidenceNeverBecomesAuthenticated |
| BR-006 | Daemon reports error and does not falsely claim bridge fresh | GO_BRIDGE_HOSTED | NOT_RUN | UNMAPPED |
| BR-007 | Refused on both preflight and post-open checks | GO_BRIDGE_HOSTED | NOT_RUN | UNMAPPED |
| BR-008 | Unique increasing sequences and no torn envelope | GO_BRIDGE_HOSTED | NOT_RUN | UNMAPPED |
| BR-009 | Only permitted availability reason uses fallback | GO_BRIDGE_HOSTED | NOT_RUN | UNMAPPED |
| CI-001 | Plan/preflight/invoke exact expected jobs and schema | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| CI-002 | Tests vet contracts final seal module all execute nonempty | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| CI-003 | Expected tasks and exact test counts; not skipped | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| CI-004 | Both dependent Go and Android lanes scheduled | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| CI-005 | Full validation rather than unsafe skip | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| CI-006 | Reused/watched, no duplicate dispatch | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| CI-007 | Actual check lines and meaningful failed logs printed | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| CI-008 | Artifact refused as unqualified | CI_AND_MODULE_HOSTED | NOT_RUN | TestR09EModuleCandidateEvidenceRefusals |
| CI-009 | Gate refuses qualified success | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| CI-010 | Overlay refuses and preserves all unrelated files | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| CI-011 | Wrong runtime refused, identical valid ZIP hashes | CI_AND_MODULE_HOSTED | NOT_RUN | TestR09EModuleCandidateEvidenceRefusals |
| CI-012 | Previous stage remains usable and recoverable | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| CI-013 | Fail policy check until immutable SHA used | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| CI-014 | Package/signer/hash/version exact; emulator != release | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| CI-015 | Required full tests preserved with no missing dependency | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| CI-016 | Checkout and pre-existing edits preserved | CI_AND_MODULE_HOSTED | NOT_RUN | UNMAPPED |
| EM-001 | Disconnected/nonemulator/wrong API safely refused | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-002 | Exactly one matching package and source/run/attempt | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-003 | MainActivity renders offline and actionable guidance | ANDROID_EMULATOR_HOSTED | NOT_RUN | launcherActivityReachesResumedWithoutTaskerOrRoot |
| EM-004 | Each screen interactive and correct back stack | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-005 | Save, error and reload outcome visible | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-006 | Correct persistence/validation and no dropped changes | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-007 | Reject unsafe input and explain why | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-008 | UI does not report queued as applied | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-009 | Recovery/error message displayed without ANR | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-010 | No root execution or premature Tasker success | ANDROID_EMULATOR_HOSTED | NOT_RUN | unsignedActionFailsWithoutStartingRoot |
| EM-011 | Valid bounded config and correct return/result | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-012 | Cancel status and notification consistent | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-013 | Data and index preserved | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-014 | Journal persists; no duplicate command after restart | ANDROID_EMULATOR_HOSTED | NOT_RUN | persistedPendingCommandSurvivesInMemoryProcessStateLoss |
| EM-015 | No data loss/duplicate mutation | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-016 | No clipping or obscured buttons/insets | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-017 | No clipped inaccessible controls, correct translations | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-018 | Actual focus/labels/targets pass | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-019 | Disabled/unavailable state; no root/false success | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-020 | No duplicate commands and correct persisted state | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-021 | Sensitive action requires unlock/explicit confirmation | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-022 | Unique dimensions/hash, no user secrets or raw private log | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-023 | App launches, Tasker fallback and no unsupported call | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| EM-024 | No API-specific SecurityException, correct sender rules | ANDROID_EMULATOR_HOSTED | NOT_RUN | UNMAPPED |
| FX-001 | Exact restore for modified cores | GO_FAKE_BACKEND_HOSTED | NOT_RUN | TestR09FAdversarialCrashSchedule, TestR09CFakeAndroidEffectRoundTrip |
| FX-002 | Never offlines cpu0 and refuses unsupported core | GO_FAKE_BACKEND_HOSTED | NOT_RUN | UNMAPPED |
| FX-003 | Unresolved journal persists and no rollback PASS | GO_FAKE_BACKEND_HOSTED | NOT_RUN | TestR09FJournalReleasePermutation, TestR09DRestoreFailureRetainsJournal |
| FX-004 | Effect not changed and failure identified | GO_FAKE_BACKEND_HOSTED | NOT_RUN | TestR09CFakeAndroidProbeAndWriteFaults |
| FX-005 | Restore exact original state; no spurious enable | GO_FAKE_BACKEND_HOSTED | NOT_RUN | TestR09FExternalStateOwnership, TestR09CFakeAndroidPreexistingExternalState |
| FX-006 | Snapshot retained and rollback report truthful | GO_FAKE_BACKEND_HOSTED | NOT_RUN | UNMAPPED |
| FX-007 | Exact restore or refusal on uncertain state | GO_FAKE_BACKEND_HOSTED | NOT_RUN | TestR09FExternalStateOwnership, TestR09CFakeAndroidPreexistingExternalState |
| FX-008 | Exact original value/presence or error | GO_FAKE_BACKEND_HOSTED | NOT_RUN | TestR09CFakeAndroidEffectRoundTrip |
| FX-009 | Only newly suspended owned apps unsuspended | GO_FAKE_BACKEND_HOSTED | NOT_RUN | UNMAPPED |
| FX-010 | Restore exact originally owned package set | GO_FAKE_BACKEND_HOSTED | NOT_RUN | UNMAPPED |
| FX-011 | Critical protected packages never suspended/killed | GO_FAKE_BACKEND_HOSTED | NOT_RUN | UNMAPPED |
| FX-012 | Never renice unrelated restarted process | GO_FAKE_BACKEND_HOSTED | NOT_RUN | UNMAPPED |
| FX-013 | Idempotent intended nice values only | GO_FAKE_BACKEND_HOSTED | NOT_RUN | UNMAPPED |
| FX-014 | Status flags nonreversible action, no fake rollback | GO_FAKE_BACKEND_HOSTED | NOT_RUN | UNMAPPED |
| FX-015 | Clear failed effect, no claimed applied state | GO_FAKE_BACKEND_HOSTED | NOT_RUN | TestR09CFakeAndroidProbeAndWriteFaults |
| FX-016 | Per-effect reference/desired state reconciled | GO_FAKE_BACKEND_HOSTED | NOT_RUN | TestR09FExternalStateOwnership |
| FX-017 | No state mutation without recovery journal | GO_FAKE_BACKEND_HOSTED | NOT_RUN | TestR09FAdversarialCrashSchedule, TestR09CFakeAndroidDurableJournalGate |
| FX-018 | No unsafe package handling or resource exhaustion | GO_FAKE_BACKEND_HOSTED | NOT_RUN | UNMAPPED |
| FX-019 | Correct priority/debounce/reversal transitions | GO_FAKE_BACKEND_HOSTED | NOT_RUN | UNMAPPED |
| FX-020 | No missed boot or spurious low-power activation | GO_FAKE_BACKEND_HOSTED | NOT_RUN | UNMAPPED |
| PF-001 | Measured median/p95 and regressions flagged against approved baseline | PERFORMANCE_REGRESSION | NOT_RUN | UNMAPPED |
| PF-002 | Bounded latency/error metrics and no false timeout completion | PERFORMANCE_REGRESSION | NOT_RUN | UNMAPPED |
| PF-003 | Zero ANRs and forbidden main-thread disk/root I/O | PERFORMANCE_REGRESSION | NOT_RUN | UNMAPPED |
| PF-004 | No leftover worker/thread/process and journal consistent | PERFORMANCE_REGRESSION | NOT_RUN | UNMAPPED |
| PF-005 | No continuous high-rate polls/wakelocks; measured budget | PERFORMANCE_REGRESSION | NOT_RUN | UNMAPPED |
| PF-006 | Bounded CPU/memory/FD and no journal growth leak | PERFORMANCE_REGRESSION | NOT_RUN | UNMAPPED |
| PF-007 | No text clipping/overlap, real screen images and metadata | PERFORMANCE_REGRESSION | NOT_RUN | UNMAPPED |
| PF-008 | Before/after data and confounders recorded, no invented savings | PERFORMANCE_REGRESSION | NOT_RUN | UNMAPPED |
| RD-001 | API, kernelSU/Magisk version and module status only; no mutation | ANDROID_PHYSICAL_OPT_IN | NOT_RUN | UNMAPPED |
| RD-002 | Exact artifact hash, install success and recovery record | ANDROID_PHYSICAL_OPT_IN | NOT_RUN | UNMAPPED |
| RD-003 | Daemon comes up, config exists, boot event observed | ANDROID_PHYSICAL_OPT_IN | NOT_RUN | UNMAPPED |
| RD-004 | Expected transitions and no duplicate effect | ANDROID_PHYSICAL_OPT_IN | NOT_RUN | UNMAPPED |
| RD-005 | Corresponding event state maintained and reversed | ANDROID_PHYSICAL_OPT_IN | NOT_RUN | UNMAPPED |
| RD-006 | CLI and app observed daemon-confirmed status | ANDROID_PHYSICAL_OPT_IN | NOT_RUN | UNMAPPED |
| RD-007 | Completion and state match exact command outcome | ANDROID_PHYSICAL_OPT_IN | NOT_RUN | UNMAPPED |
| RD-008 | Correct labels, lockscreen, off/on/update without app | ANDROID_PHYSICAL_OPT_IN | NOT_RUN | UNMAPPED |
| RD-009 | Journal recovers, no duplicate operation | ANDROID_PHYSICAL_OPT_IN | NOT_RUN | UNMAPPED |
| RD-010 | Cancel/timeout terminates only owned process tree | ANDROID_PHYSICAL_OPT_IN | NOT_RUN | UNMAPPED |
| RD-011 | Exact before/after verified and cleanup pass | ANDROID_PHYSICAL_OPT_IN | NOT_RUN | UNMAPPED |
| RD-012 | CPU/GMS/Wi-Fi stress verified and restored, no primary-phone run | ANDROID_PHYSICAL_OPT_IN | NOT_RUN | UNMAPPED |
| RD-013 | User config preserved/upgrades; restore/uninstall reports truth | ANDROID_PHYSICAL_OPT_IN | NOT_RUN | UNMAPPED |
