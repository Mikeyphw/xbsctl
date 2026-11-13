# R05B — configuration generation and recovery safety

Source pin: `735ee457c9376a0887cfb8ef2a2a3123eb048bb6`, R05A PR #15 (hosted successful at run 43372144786).

- `config.LoadWithSourceDigest` hashes the actual bytes it parsed into the validated daemon configuration, refusing symlinks, malformed JSON, and oversized files. No config content is logged or published; only the SHA-256 is visible.
- `XtremeBSd` tracks the loaded config generation and includes it in the read-only status snapshot after successful reload.
- `XBSctl reload --wait --expect-config-sha256 <digest>` requires daemon acknowledgement, observed runtime transition and exact same config SHA-256. Mismatch produces unknown/nonzero rather than replaying.
- Android config transaction promotion, manual rollback, and manual recovery now require matched daemon generation; reload timeout/failure leaves `recovery_required` and the preserved journals, never silent auto reapplication.
- New transaction refuses unresolved/missing/symlinked prior latest pointer; old committed/rolled_back/recovered transactions can be superseded under the existing process lock.
- R05B test source includes Go digest/CLI tests and Android JVM shell-contract tests; hosted Gradle is the build gate. Real file mutation/ownership, API36 instrumentation, device root and Tasker end-to-end remain pending R05C/R11 and are NOT claimed qualified.
