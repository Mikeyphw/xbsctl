# Phase 29 + 30 — Security, Accessibility, Adaptive Layout, and Performance Seal

This combined phase seals exported components, text-only outbound sharing, strict managed paths, persistent one-time runtime-notification action consumption, cleartext denial, and passive security evidence. It also standardizes 48dp touch targets and accessibility headings, uses cutout/IME-aware edge-to-edge insets and predictive back, provides rail navigation at 600dp+, coalesces broadcast-driven rebuilds, and stores bounded local timing counters.

Performance counters are observational local evidence, not benchmark claims. Device Macrobenchmark/Baseline Profile measurement remains a Phase 31 release-gate task.


## r2 audit corrections
- Cancellation actions use a durable one-use per-run token across process restarts.
- Expanded windows center the primary body inside the 1120dp maximum content width.
- A whole-screen accessibility pass enforces 48dp targets and a primary heading for every XbsActivity, including direct Spinner and CheckBox controls.
- Security evidence requires the exact exported set, a verified Tasker certificate pin when configured, and fresh durable action-guard evidence.
- Release readiness consumes cached runtime UI-quality evidence rather than hardcoded layout constants.
