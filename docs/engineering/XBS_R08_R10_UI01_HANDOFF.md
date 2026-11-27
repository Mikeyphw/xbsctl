# R08/R10 UI01 — rootless presentation and tile settings

Base: `ff9e67661a76e0114a025c5e516524750ba30e88`, main run `45936373612` (success). All four historical archive digests were independently checked. At both 360dp and 393dp, redacted dark/light bytes were identical: prior screenshots do not qualify theme coverage.

The new theme follows resource configuration; the pure palette policy refuses low contrast, inversion and translucency. Main navigation and Arabic chrome, measured touch targets, actual rotation, and offline tile settings/reset cancellation have real instrumentation assertions. Hosted capture verifies observed mode, foreground, window node bounds and decoded brightness, with distinct redacted images. The hosted assertions are pending, not accepted.

- EM-015: Actual rotation preserves selected tab; editor pending request restoration still open.
- EM-016: Portrait matrix and measured navigation/insets; every screen and obscured controls remain open.
- EM-017: Actual day/night palette and Arabic navigation; full translation and control matrix remain open.
- EM-018: Measured navigation labels/targets and heading; full focus and Espresso accessibility coverage remain open.
- EM-019: Offline tile settings refuses dispatch; retained historical SystemUI callback evidence.
- EM-020: No new rapid-tap/process-kill acceptance; historical partial evidence only.
- EM-021: Reset cancellation tested, actual secure unlock remains open.
- EM-022: Reject duplicate or contradictory theme capture; content privacy and other dimensions remain open.

All 178 original tasks, 131 cases and 26 findings are preserved; no original case is promoted. No physical/root/release qualification, full translation, full accessibility, editor recovery, secure unlock or landscape screenshot claims.
