#!/usr/bin/env python3
"""R03C read-only Android API36 sensor compatibility diagnostic.

Never runs su, adb root/remount, appops, settings put, module installers,
or any command with device-side writes. This does NOT qualify root effects.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ACK = "I_AUTHORIZE_READ_ONLY_DEVICE_PROBE"
SERIAL = re.compile(r"^[A-Za-z0-9._:-]{1,80}$")

class ProbeRefusal(Exception):
    pass

def exec_adb(serial, args):
    try:
        p = subprocess.run(["adb", "-s", serial, "shell", *args],
                           capture_output=True, text=True, timeout=15, check=False)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise ProbeRefusal("adb read failed: " + str(e))
    if p.returncode or len(p.stdout)>50000:
        raise ProbeRefusal("adb read refused for: " + " ".join(args))
    return p.stdout.strip()

def parse_wakefulness(raw):
    vals = re.findall(r"(?m)^\s*mWakefulness=([^\s]+)\s*$",raw)
    if len(vals)!=1 or vals[0] not in ("Awake","Asleep"):
        raise ProbeRefusal("missing/duplicate/unsupported power wakefulness")
    return vals[0]

def probe(serial, runner=exec_adb):
    if not SERIAL.fullmatch(serial):
        raise ProbeRefusal("invalid ADB serial")
    sdk = runner(serial, ["getprop", "ro.build.version.sdk"])
    abi = runner(serial, ["getprop", "ro.product.cpu.abi"])
    if sdk != "36" or abi != "arm64-v8a":
        raise ProbeRefusal("expected Android API36 arm64-v8a, got sdk="+sdk+" abi="+abi)
    status = runner(serial,["cat","/sys/class/power_supply/battery/status"]).lower()
    if status not in ("charging","discharging","not charging","full"):
        raise ProbeRefusal("unrecognized battery status")
    capacity = runner(serial,["cat","/sys/class/power_supply/battery/capacity"])
    if not capacity.isdigit() or not (0 <= int(capacity) <= 100):
        raise ProbeRefusal("invalid battery capacity")
    awake = parse_wakefulness(runner(serial,["dumpsys","power"]))
    return {"schema":"xbs-r03c-readonly-api36/v1",
            "status":"READ_ONLY_SENSOR_PROBE_PASS_NOT_ROOT_ACCEPTANCE",
            "sdk":36,"abi":"arm64-v8a", "battery_state":status,
            "battery_percent":int(capacity),"wakefulness":awake,
            "device_mutation_commands":0,
            "limitations":"No root effects or profile transitions executed; human physical acceptance pending"}

def main(argv=None):
    a=argparse.ArgumentParser(description="Explicitly authorized read-only Android API36 sensor probe")
    a.add_argument("--serial",required=True)
    a.add_argument("--ack",required=True)
    a.add_argument("--report",help="Optional local diagnostic JSON report path")
    args=a.parse_args(argv)
    if args.ack != ACK:raise ProbeRefusal("read-only acknowledgement required")
    report=probe(args.serial)
    payload=json.dumps(report,sort_keys=True,indent=2)+"\n"
    if args.report:
        dst=Path(args.report).expanduser().resolve()
        if dst.exists():raise ProbeRefusal("report exists; refusing overwrite")
        dst.parent.mkdir(parents=True,exist_ok=True)
        import os
        fd=os.open(str(dst),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,"w",encoding="utf8") as f:
            f.write(payload)
            f.flush();os.fsync(f.fileno())
    print(payload,end="")

if __name__ == "__main__":
    try:main()
    except ProbeRefusal as e:
        print("XBS_R03C_READ_ONLY_PROBE_REFUSED: " + str(e),file=sys.stderr)
        sys.exit(1)
