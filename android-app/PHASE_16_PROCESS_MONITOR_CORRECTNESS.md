# Phase 16: Process Monitor Correctness Pass

Baseline: combined Phase 14 r2 + Phase 15 commit `2a0c14b`.

## Scope

- Parse aggregate `/proc/stat` CPU time without adding `guest` and `guest_nice` a second time.
- Use the kernel-reported online CPU row count for process CPU scaling instead of the app runtime processor count.
- Read NUL-separated `/proc/<pid>/cmdline` and retain `/proc/<pid>/comm` as the empty/inaccessible fallback.
- Parse `/proc/<pid>/stat` after the final `) ` boundary so spaces and closing parentheses in `comm` do not shift fields.
- Capture process start ticks and use `pid:startTicks` as the delta identity, preventing recycled PIDs from inheriting previous CPU usage.
- Capture parent PID, executable link, SELinux context, sample timestamp, and explicit observed/unavailable flags.
- Count visible, readable, restricted, malformed, cmdline-unavailable, executable-unavailable, and context-unavailable entries.
- Surface partial `/proc` visibility in the monitor, Overview cache card, and support bundle format 18.
- Bump the process cache key to `process_snapshot_v4` so older rows without stable identity are never used for CPU deltas.

## Safety and behavior

The scanner remains read-only and root-backed. It does not change process state, mount namespaces, SELinux policy, or permissions. Missing optional process fields are represented as unavailable rather than synthesized. CPU values are emitted only when two samples have the same PID and start ticks.

## Preflight

- Pure-Java parser and CPU-delta harness.
- Real host `/proc` shell scan parsed successfully.
- Generated shell passed `sh -n`.
- Process domain, repository, monitor activity, and support-bundle sources compiled against focused API stubs with `-Xlint:all -Werror`.
- Repository source contracts cover guest exclusion, Android kernel field variation, spaces in `comm`, empty cmdline, PID reuse, inaccessible files, multi-core scaling, extended evidence, and no resource churn.

Devtool validation is intentionally disabled in the artifact manifest by user request. Apply with `--no-validate`.
