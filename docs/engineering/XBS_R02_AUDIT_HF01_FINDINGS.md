# R02 consolidated audit — HF01

## Scope and verification truth

Audited the original consolidated R02 ZIP against R02-01 through R02-13 and its public delivery checklist. The original ZIP has the planned source components, but its independent recovery evidence check was incomplete. Source patches/tests are provided; full repository Go, hosted CI, API36 emulator, and physical-root acceptance are **not** claimed as passed by the ZIP self-test.

## Issues fixed

1. **Orphaned applying-phase evidence**: `PendingUnknown` previously scanned only `.work` files. If the `.work` name was absent while a durable `ID.applying` remained, daemon startup could miss an unresolved privileged operation. Now it scans phase records independently, publishes an immutable `unknown` receipt, and refuses profile reactivation.
2. **Orphaned unknown acknowledgement**: an `unknown` ack remains a blocking record even if its `.work` claim is absent. Both startup and read-only inspection report it.
3. **Unreadable/forged orphan records**: unsafe phase symlinks, invalid identities, malformed acknowledgement records and unsupported record states fail closed.
4. **Authenticated event bridge errors were silently discarded**: `writeEventChanges` now records errors in daemon status. It does not falsely turn an already-executed root command into a replayable failure.
5. **Spool ownership and paths**: verify owner UID before chmod, refuse untrusted parent symlinks, and allow only Android’s root-owned `/data/data` alias to `/data/user/0`.
6. **Strictly read-only inspection**: recovery views do not change directory mode, even for unsafe legacy spools; they refuse and preserve evidence instead.
7. **Regression coverage**: ten new control-package tests, one daemon startup test, and one event bridge failure-reporting test. The launcher additionally verifies the orphan-evidence negative mutation is caught.

## Delivered (source-level; device qualification still pending)

- R02A private queue, exact request validation, no-follow spool, no-overwrite publication, singleton daemon lock, bounded files and immutable acknowledgements.
- R02B durable `claimed`/`applying` records, interrupted command `unknown` outcomes, startup reactivation barrier and preserved recovery journal.
- Remaining R02: pre-execution TTL, cancellation only before claim, read-only `runtime recovery` inspection, `runtime cancel`, config validation, checked shutdown persistence, versioned status/event records, controller JSON/text routes and ledger updates.

## Acceptance boundaries and explicit non-delivery

- Unknown root outcomes are **never automatically cleared**. A trustworthy device-specific operator reconciliation and restart acceptance gate requires separate design and physical evidence.
- A client timeout never interrupts an in-flight Android root operation.
- Physical-root crash timing, Kotlin/Android emulator behavior and device-specific permission semantics have not been qualified by Go tests.
- `quit`/one-shot event semantics are retained from the existing compatibility contract; this hotfix does not redefine their restoration policy.
- Hosted CI must run on the exact pushed SHA; an initial absence of GitHub checks is not a failure.

## Next three overlays

1. R03A — Automatic charging/screen/battery event lifecycle and cancellation.
2. R04A — Magisk/KernelSU staging, integrity checks and rollback.
3. R05A — Native Android Companion UX, permissions and Tasker integration.
