# R03B — Lifecycle completion (source implementation, unqualified on device)

Base: R03A PR #9 at `21603ae162dc2e3da8fa1bc7089740cc0ca9d005`.

- Explicit header.automatic_boot_enabled (default false) gated by automatic_events_enabled.
- Durable per-physical-boot intent, UUID validation, at-most-once application, restart replay refusal and safe quit restoration.
- screen_off event `keep_on_charge` governs screen-off coexistence while plugged in; charging suppresses low-power.
- Lease-aware priority order: charging, screen_off, low_power. Nonoverlapping desired domains can run together; conflicts defer lower priority. Manually owned effects are not stopped or stolen.
- Durable automatic_desired, automatic_observed, automatic_pending and automatic_blocked state separate from actual active_profiles. No sensor state inferred on recovery.
- No per-device acceptance or recovery authority claimed. Full Go suite and GitHub CI required.

**Limitations:** A reboot with a boot intent left incomplete requires deliberate reconciliation. The daemon does not automatically retry. The POSIX runtime must support durable fsync. Physical API36 qualification is pending.
