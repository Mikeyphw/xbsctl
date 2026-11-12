#!/usr/bin/env python3
"""Non-mutating API36 sensor inventory. Unavailable wakefulness is never guessed.

All device access is limited to the explicit adb shell READ_ALLOWLIST below.
No root, su, settings writes, interactive input, or privileged profiles.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ACK = "I_AUTHORIZE_READ_ONLY_DEVICE_PROBE"
SERIAL = re.compile(r"^[A-Za-z0-9._:-]{1,80}$")
MAX_OUTPUT_BYTES = 2 * 1024 * 1024
READ_ALLOWLIST = frozenset({
    ("getprop", "ro.build.version.sdk"),
    ("getprop", "ro.product.cpu.abi"),
    ("cat", "/sys/class/power_supply/battery/status"),
    ("cat", "/sys/class/power_supply/battery/capacity"),
    ("dumpsys", "power"),
    ("dumpsys", "input_method"),
    ("dumpsys", "display"),
})

class ProbeRefusal(Exception):
    pass


def _diagnostic(value):
    """Keep useful ADB error context bounded and printable."""
    return " ".join(str(value).split())[:280] or "no diagnostic text"


def exec_adb(serial, args):
    if not SERIAL.fullmatch(serial):
        raise ProbeRefusal("invalid ADB serial")
    if tuple(args) not in READ_ALLOWLIST:
        raise ProbeRefusal("ADB command is not in the read-only allowlist")
    try:
        p = subprocess.run(["adb", "-s", serial, "shell", *args],
                           capture_output=True, text=True, errors="replace",
                           timeout=20, check=False)
    except subprocess.TimeoutExpired:
        raise ProbeRefusal("ADB read timed out for: " + " ".join(args))
    except OSError as e:
        raise ProbeRefusal("ADB read unavailable for: " + " ".join(args) + ": " + _diagnostic(e))
    if p.returncode:
        reason = p.stderr or p.stdout
        raise ProbeRefusal("ADB read returned exit=" + str(p.returncode) + " for: " +
                           " ".join(args) + " (" + _diagnostic(reason) + ")")
    if len(p.stdout.encode("utf-8")) > MAX_OUTPUT_BYTES:
        raise ProbeRefusal("ADB read exceeded 2 MiB for: " + " ".join(args))
    return p.stdout.strip()


def parse_wakefulness(raw):
    values = re.findall(r"(?m)^\s*mWakefulness\s*=\s*([A-Za-z]+)\s*$", raw)
    if len(values) != 1 or values[0] not in ("Awake", "Asleep"):
        raise ProbeRefusal("missing, duplicate, or unsupported power wakefulness")
    return values[0]


def parse_interactive(raw):
    values = re.findall(r"(?m)^\s*mInteractive\s*=\s*(true|false)\b", raw, flags=re.I)
    if len(values) != 1:
        raise ProbeRefusal("missing or ambiguous input-method interactivity")
    return values[0].lower() == "true"


def parse_screen_state(raw):
    values = re.findall(r"\bmScreenState\s*=\s*(ON|OFF|DOZE|DOZE_SUSPEND)\b", raw, flags=re.I)
    if not values or len(set(x.upper() for x in values)) != 1:
        raise ProbeRefusal("missing or ambiguous display screen state")
    return values[0].upper()


def probe(serial, runner=exec_adb, require_wakefulness=False):
    if not SERIAL.fullmatch(serial):
        raise ProbeRefusal("invalid ADB serial")
    sdk = runner(serial, ["getprop", "ro.build.version.sdk"])
    abi = runner(serial, ["getprop", "ro.product.cpu.abi"])
    if sdk != "36" or abi != "arm64-v8a":
        raise ProbeRefusal("expected Android API36 arm64-v8a, got sdk=" + sdk + " abi=" + abi)
    battery = runner(serial, ["cat", "/sys/class/power_supply/battery/status"]).strip().lower()
    if battery not in ("charging", "discharging", "not charging", "full"):
        raise ProbeRefusal("unrecognized battery status")
    capacity = runner(serial, ["cat", "/sys/class/power_supply/battery/capacity"]).strip()
    if not re.fullmatch(r"\d{1,3}", capacity) or not (0 <= int(capacity) <= 100):
        raise ProbeRefusal("invalid battery capacity")

    wakefulness = None
    interactive = None
    screen_state = None
    observation_source = None
    warnings = []
    try:
        wakefulness = parse_wakefulness(runner(serial, ["dumpsys", "power"]))
        observation_source = "dumpsys power"
    except ProbeRefusal as error:
        warnings.append("wakefulness unavailable: " + str(error))
        # Fallbacks diagnose interactivity or display state only. Neither
        # is equivalent to PowerManager's mWakefulness; never synthesize it.
        try:
            interactive = parse_interactive(runner(serial, ["dumpsys", "input_method"]))
            observation_source = "dumpsys input_method"
        except ProbeRefusal as fallback_error:
            warnings.append("interactivity unavailable: " + str(fallback_error))
            try:
                screen_state = parse_screen_state(runner(serial, ["dumpsys", "display"]))
                observation_source = "dumpsys display"
            except ProbeRefusal as display_error:
                warnings.append("display state unavailable: " + str(display_error))

    if require_wakefulness and wakefulness is None:
        raise ProbeRefusal("strict wakefulness required; " + "; ".join(warnings))
    complete = wakefulness is not None
    return {
        "schema": "xbs-r03c-readonly-api36/v2",
        "status": "READ_ONLY_SENSOR_PROBE_PASS_NOT_ROOT_ACCEPTANCE" if complete
                  else "READ_ONLY_SENSOR_PROBE_PARTIAL_NOT_ROOT_ACCEPTANCE",
        "sdk": 36,
        "abi": "arm64-v8a",
        "battery_state": battery,
        "battery_percent": int(capacity),
        "wakefulness": wakefulness,
        "interactive": interactive,
        "screen_state": screen_state,
        "observation_source": observation_source,
        "warnings": warnings,
        "device_mutation_commands": 0,
        "limitations": ("No root effects or profile transitions executed; "
                        "fallback interactivity/display does not confirm wakefulness; "
                        "physical acceptance pending"),
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description="Explicitly authorized read-only Android API36 sensor probe")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--ack", required=True)
    parser.add_argument("--require-wakefulness", action="store_true",
                        help="Refuse a partial result if PowerManager wakefulness is unavailable")
    parser.add_argument("--report", help="Optional new local JSON report (never overwritten)")
    args = parser.parse_args(argv)
    if args.ack != ACK:
        raise ProbeRefusal("read-only acknowledgement required")
    report = probe(args.serial, require_wakefulness=args.require_wakefulness)
    payload = json.dumps(report, sort_keys=True, indent=2) + "\n"
    if args.report:
        dst = Path(args.report).expanduser().resolve()
        if dst.exists():
            raise ProbeRefusal("report exists; refusing overwrite")
        dst.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(str(dst), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as file:
            file.write(payload)
            file.flush()
            os.fsync(file.fileno())
    print(payload, end="")

if __name__ == "__main__":
    try:
        main()
    except ProbeRefusal as e:
        print("XBS_R03C_HF01_READ_ONLY_PROBE_REFUSED: " + str(e), file=sys.stderr)
        sys.exit(1)
