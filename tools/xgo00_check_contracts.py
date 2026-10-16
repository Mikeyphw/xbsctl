#!/usr/bin/env python3
from __future__ import annotations

import ast
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
XBS = ROOT / "apps" / "xbs"
CONTRACTS = XBS / "migration" / "contracts" / "v1"
REQUIRED = {
    "android_catalog.json",
    "capabilities.json",
    "cli_commands.json",
    "command_status_schema.json",
    "config_schema.json",
    "event_bridge_envelope.json",
    "machine_output_status_schema.json",
    "xbsctl_commands.json",
}
EXPECTED_XBS_CLI_COMMANDS = 17
EXPECTED_COMPAT_COMMANDS = 39
EXPECTED_XBSCTL_COMMANDS = 11


def load(name: str) -> object:
    path = CONTRACTS / name
    if not path.is_file():
        raise AssertionError(f"missing contract fixture: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def _go_quoted_strings(text: str) -> list[str]:
    return [bytes(value, "utf-8").decode("unicode_escape") for value in re.findall(r'"((?:\\.|[^"\\])*)"', text)]


def compatibility_source_constants() -> tuple[list[str], dict[str, list[str]]]:
    source = XBS / "internal" / "compat" / "compat.go"
    text = source.read_text(encoding="utf-8")
    ids_match = re.search(r"var\s+commandIDs\s*=\s*\[\]string\s*\{(?P<body>.*?)\n\}", text, re.S)
    paths_match = re.search(r"var\s+commandPaths\s*=\s*map\[string\]\[\]string\s*\{(?P<body>.*?)\n\}", text, re.S)
    if ids_match is None or paths_match is None:
        raise AssertionError("could not read commandIDs/commandPaths from internal/compat/compat.go")
    command_ids = _go_quoted_strings(ids_match.group("body"))
    command_paths: dict[str, list[str]] = {}
    for match in re.finditer(r'"((?:\\.|[^"\\])*)"\s*:\s*\{(?P<body>.*?)\}', paths_match.group("body"), re.S):
        command_id = bytes(match.group(1), "utf-8").decode("unicode_escape")
        command_paths[command_id] = _go_quoted_strings(match.group("body"))
    if set(command_ids) != set(command_paths):
        raise AssertionError("compat Go command id/path maps are out of sync")
    return command_ids, command_paths


def android_catalog_ids() -> list[str]:
    source = XBS / "android-app" / "app" / "src" / "main" / "java" / "io" / "github" / "mikeceead" / "xbscompanion" / "XbsCliCatalog.java"
    text = source.read_text(encoding="utf-8")
    ids = []
    for match in re.finditer(r"(?:c|ctl|ctlWait|ctlEvent|special)\(\"([A-Za-z0-9_]+)\"", text):
        ids.append(match.group(1))
    return ids


def main() -> int:
    missing = sorted(name for name in REQUIRED if not (CONTRACTS / name).is_file())
    if missing:
        raise AssertionError(f"missing contract files: {missing}")

    cli = load("cli_commands.json")
    commands = cli["data"]["commands"]
    if len(commands) != EXPECTED_XBS_CLI_COMMANDS:
        raise AssertionError(f"xbs CLI command count drift: {len(commands)}")

    capabilities = load("capabilities.json")
    advertised = capabilities["data"]["compatibility"]["commands"]
    advertised_ids = [row["id"] for row in advertised]
    source_ids, source_paths = compatibility_source_constants()
    if advertised_ids != source_ids:
        raise AssertionError("capabilities command IDs no longer match compatibility.py")
    for row in advertised:
        if row["path"] != source_paths[row["id"]]:
            raise AssertionError(f"command path drift for {row['id']}")
    if len(advertised) != EXPECTED_COMPAT_COMMANDS:
        raise AssertionError(f"compatibility command count drift: {len(advertised)}")

    xbsctl = load("xbsctl_commands.json")
    if len(xbsctl["commands"]) != EXPECTED_XBSCTL_COMMANDS:
        raise AssertionError(f"XBSctl command count drift: {len(xbsctl['commands'])}")

    android = load("android_catalog.json")
    frozen_android_ids = [row["id"] for row in android["commands"]]
    live_android_ids = android_catalog_ids()
    if frozen_android_ids != live_android_ids:
        raise AssertionError("Android XbsCliCatalog command list drifted from frozen fixture")

    event_bridge = load("event_bridge_envelope.json")
    if event_bridge["format_version"] != 1 or event_bridge["algorithm"] != "hmac-sha256":
        raise AssertionError("event bridge envelope contract drifted")

    print(
        "XGO00 contracts valid: "
        f"cli={len(commands)} compat={len(advertised)} xbsctl={len(xbsctl['commands'])} "
        f"android={len(frozen_android_ids)} event_bridge=v{event_bridge['format_version']}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"XGO00 contract check failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
