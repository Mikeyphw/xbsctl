# R02A — Secure daemon and queue lifecycle (source-bounded)

Source: `Mikeyphw/xbsctl` branch `feature/xbs-r01e-restoration-hardening-20261112`, commit `e273ac711e5d334c9552f3fb7d386aa15ad18066`.

This tranche tightens queue and daemon operations without root-device mutations:

- Request and acknowledgement spool directories: owner-only mode 0700 (legacy 0755 directories are upgraded), leaf symlink refusal. Bytes: 0600 regular files, bounded to 4096 bytes on read, `O_NOFOLLOW`.
- Queue and acknowledgement publication: unique temporary file with O_EXCL, file sync, hardlink no-replace publish, directory fsync. Conflicting receipts are immutable.
- Requests: valid ID, ID/filename match, bounded command/event grammar, malformed or unsafe requests quarantined, interrupted unacknowledged `.work` never replayed.
- Daemon exclusive runtime flock prevents two daemon processes from consuming `.work` concurrently.
- Legacy single-line control file remains for compatibility, but cannot be symlink/FIFO/oversized and is consumed through an opened handle. It is *not* yet a transactional inbox and remains a migration task.
- Controller --wait now reports a durable failed receipt as `failed` rather than misreporting `timeout`.

## Residual risks / strict gates

- Parent-path symlinks and nonlocal/SAF shared filesystem semantics require a secure broker/openat2 design (follow-up); this tranche validates final spool directories and no-follow regular files only.
- File mode and fsync requirements can refuse Android shared storage lacking POSIX semantics; move the runtime spool to private internal storage or provide a verified privileged broker instead of disabling safety.
- A crash between kernel operation and receipt can still yield a genuinely unknown outcome; no automatic replay. Physical API36/KernelSU reconciliation needed.
- Unix `flock` is cooperative and protects participating daemon instances, not arbitrary external root processes.
- Hardlink publication relies on a same-filesystem POSIX spool; unsupported file systems fail closed.
- R02-04 durable executing lifecycle and R02-10 cancellable bounded execution remain incomplete.

## Qualification

Isolated Go tests and race+vet are preflight only. The launcher requires real `go test ./...`, Go vet, contracts and seal prior to commit and SHA-bound hosted Go check after push. Android/root qualification NOT implied.

## Next 3 overlays

1. R02B — durable command phase receipt and crash fault injection with secure internal queue transport.
2. R03A — charging/screen/battery event observation, precedence and cancellation.
3. R04A — Magisk/KernelSU install/upgrade/rollback/artifact safety.

## HF01 — Android-safe atomic queue publication

The initial R02A archive used `os.Link(tmp, dst)` to obtain no-replace
publication. Android/Termux can reject hard-link creation under app SELinux,
even on a private POSIX filesystem. Four Go tests exposed this as `permission
denied`. HF01 substitutes Linux/Android `renameat2(RENAME_NOREPLACE)` with
an exact syscall-number mapping for supported architectures; its intent is
unchanged: a fully fsynced temporary file becomes visible atomically without
overwriting a preexisting request or receipt. It refuses unsupported kernels,
filesystems and architectures rather than falling back to overwriting rename.

New tests assert immutable destination collision handling, the successful
same-directory path, and cross-directory refusal. The rest of the R02A
security, recovery and ledger implementation remains source pinned to R01E.
R02A still requires full Go CI and Android/physical qualification.
