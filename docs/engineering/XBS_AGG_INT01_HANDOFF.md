# AGG-INT01 — direct-main integration and evidence handback

Historical PR #37: `455c7493ee3b14e7ac5d0595d82daf2f69983e0f`, tree
`ec485329264b86501fd70e76accc8e37ac060071`. All three required PR workflows
succeeded at attempt 1. Seven downloaded artifact archives matched their provider
SHA-256 and size; replay using `python3 -B scripts/ci/aggregate_handback.py
--archives-dir <directory>` with the seven `xbs-*-hf01.zip` inputs. The JSON
ledger records member digests, source/run/attempt, named observations, scope and
all original 178 task / 131 case / 26 finding dispositions. Archive replay is
historical, not a new run. Checkout-only validation checks the pinned observation
and original inventories, not the archive bytes. No original case is promoted.

## Integration policy

Candidate branch `feature/xbs-aggregate-main-qualification-20261127` is one
native-delivered commit directly above HF01; PR target `main` is pinned at
`c52561dfa203090befb680a058aa5e5e79870f1b`. PR #37 remains historical evidence;
no existing branch is rewritten or closed. Native delivery requires fresh CI on
all three exact workflow IDs for the candidate. Candidate merge-tree preview
must match its tree exactly. No merge authorization is embedded. After explicit
approval only a regular two-parent merge (main first, reviewed INT01 candidate
second, candidate tree unchanged) is supported by the main push gate. Squash,
rebase, other parents, drifted main, and merge-resolution changes are refused.
Main pushes and main manual runs validate the actual merge commit and source,
never count as replacement PR acceptance. The next reviewed continuation must
extend the source policy; arbitrary future commits do not inherit acceptance.

## Remaining work

API36: 15 named Quick Settings, 3 navigation, 2 external-force-stop stage and
7 security assertions. API26/34: 4/5 named assertions. Historical Go 535 passed,
2 approved skips; JVM 223 passed. R09F coverage and phase receipts verified.
Seven rootless cold starts measured p50 1049 ms, p95 1383 ms, max PSS 65173 KiB.
No replay-absence, real root effects, OEM, battery, signing or release claim.
The two requested light/dark captures at each width have identical redacted
bytes. Locale, RTL, accessibility, clipping, and full privacy inspection remain
open; no theme acceptance is inferred. Fourteen original cases have partial
artifact links; all 131 original cases still need full independent acceptance.
Other original tasks/findings remain open. R08/R10 follow-up should complete
assertion trace and distinct theme/locale/accessibility/app journeys; R11 needs
explicit physical-device authority and rollback; R12 performance/security and
R13 signing/release remain separate. Keep original roadmap/ledger/matrix bytes.

## v1.41 source qualification repair

The exact 14 inherited Go files reported by `gofmt -l` were normalized with Go
1.23.2 `gofmt`; no Go logic changes were made. The R05A source assertions now
allow whitespace around the assignment and inequality operators while still
requiring the exact request-id field, request index and reload-time operands.
The direct-parent INT01 delta explicitly includes these paths. The previous
HF01 commit, original roadmap inventories and historical artifacts are unchanged.
