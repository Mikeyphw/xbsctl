# XBS R03D — API 36 read-only event chronology and acceptance evidence

Source base: `dbc10c545cec22edf186b1cc1a03d2891b263238` (R03C HF01, PR #12).

**This does not qualify root effects or automatic profile transitions.** It records
actual Android sensor observations from a user-initiated, nonmutating ADB read
session. It never uses root, locks/unlocks the display, modifies charging, or
queues daemon commands. Results distinguish complete observations, partial
observations, gaps, and interrupted sessions.

## Usage

Run only after obtaining user consent and while an ADB shell connection is
already available. For a physical session, manually lock/unlock the display
and attach/detach power as appropriate during the recording window. No probe
command performs those changes.

```
python3 scripts/ci/r03d_android_chronology.py --collect-chronology \
  --serial localhost:5555 --ack I_AUTHORIZE_READ_ONLY_DEVICE_PROBE \
  --output "$HOME/xbs-r03d-evidence/session-001" --samples 36 --interval 5
python3 scripts/ci/r03d_android_chronology.py \
  --verify-chronology "$HOME/xbs-r03d-evidence/session-001"
```

The parent directory must already exist, the output session directory must
not already exist, and all path components must be ordinary directories.
Observations are bounded at 240 samples or two hours. Recording uses an
append-only, fsynced JSONL file and ends with an independently verifiable
summary. If the process is interrupted, `result.json` is absent and offline
verification refuses to mark it complete.

`wakefulness` is accepted only when read as `Awake` or `Asleep` from
PowerManager. `mInteractive` and display state are *not* used to infer
wakefulness. `battery_status_changed` records observed text, not a guaranteed
USB attach/detach event. A time gap or unavailable observation interrupts
transition continuity; no missed transition is fabricated. Hysteresis is
observational only (default <=20%, release >=25%); it never invokes the
automatic-event controller.

Files: `session.json`, `samples.jsonl`, `result.json`. Per-sample SHA-256
predecessor links and a summary digest detect accidental or trivial record
modification. They are **not** signed evidence and cannot defeat a hostile
process that rewrites the entire session, nor do they prove root effects.
No ADB serial is written into session files. Local reports may contain battery
or timing information; inspect before sharing.

## Qualification boundaries

- Offline fixture unit tests, real ZIP entrypoint via fake ADB, Go inherited
  checks, and rollback tests are pre-delivery evidence, not device acceptance.
- Hosted CI at an exact committed source SHA must pass after overlay application.
- A real API36 chronology must be collected and verified by the user to
  qualify read-only **observational** evidence.
- Privileged profile effect verification, reboot/startup behavior, live
  daemon actions and recovery remain **NOT QUALIFIED**.
