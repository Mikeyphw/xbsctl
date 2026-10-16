from __future__ import annotations

import sys
from pathlib import Path

from tests._schema_fast_path import assert_schema_config_fast_path

ROOT = Path(__file__).resolve().parents[1]
for path in (
    ROOT / "packages" / "mike-cli-kit" / "src",
    ROOT / "apps" / "xbs" / "src",
):
    value = str(path)
    if value not in sys.path:
        sys.path.insert(0, value)


def test_xbs_schema_config_does_not_import_core(monkeypatch):
    payload = assert_schema_config_fast_path(
        monkeypatch,
        "xbs_cli.cli",
        "xbs_cli.core",
        ["schema", "config", "header.version"],
    )
    assert payload["data"]["entry"]["key"] == "header.version"
