# XBS R09B — deterministic backend fault matrix, module ZIP negatives and hosted receipts

## Source and scope

Base: R09A PR #21 `401d972c1c85ca429bf6676d2987cae437c07338` (Go/JVM/API36 green).
Stack: R09B PR on `feature/xbs-r09a-backend-fuzz-evidence-20261114`.
The Go packages use only existing source-owned `fakeRunner`, temporary files and
injected write/journal faults; never `SystemRunner` or real Android effects.

## New real Go cases and fault domains

- `internal/tuning/r09b_fault_matrix_test.go`: pre-effect WAL refusal, CPU
  write rollback, incomplete rollback keeps recovery preimage, simulated restart
  recovery, dry-run non-mutation. Reuses the actual `tuning.Engine` and fake runner.
- `internal/config/r09b_fault_matrix_test.go`: required-path types, null/NUL,
  invalid delay, low-power hysteresis and event grammar/enum/boolean negatives.
- `internal/control/r09b_fault_matrix_test.go`: stranded `.work` refusal and
  `.interrupted` evidence, immutable ack collision, unsafe commands/deadlines.
- `internal/module/r09b_fault_matrix_test.go`: real `archive/zip` members with
  traversal, absolute paths, duplicate/case-conflicting names, backslashes,
  symlinks and invalid/empty archives; must be rejected by `VerifyModuleZip`.

## Required hosted execution

`go-contract` executes `r09b_backend_matrix.py --hosted`, after R09A and
before R06B count/Devtool. It demands the exact 11 root tests in all four
packages, three full repetitions, and one race-enabled pass, with no skips.
A test marked PASS by plain stdout cannot qualify: the parser requires actual
`go test -json` per-test and per-package records. Its JSON receipt binds the
observed source SHA, GitHub run ID/attempt, risk inventory and false physical/
root/release claims. The receipt is uploaded with the existing module artifact.
The independent `r09b_gh_evidence.py` validates the real PR, source, run,
Go/JVM/API36 jobs, named Go step, actual log counts, and downloaded receipt.
Existing R07A and R09A independent auditors are also mandatory.

## Phase accounting

Progress on R09-02 (config negatives), R09-03/04 (simulated tuning effects and
WAL faults), R09-05 (crash/ack invariants), R09-07 (malicious module ZIPs), and
R09-08 (repeat/race). R09-01 comprehensive fake Android backend, R09-09 CLI
golden contracts, R09-10 exact risk coverage and complete R09 gate **remain
pending**. These tests do not prove physical kernel/root, power savings or
release signing. No local Gradle, ADB, root, or device mutation is permitted.
