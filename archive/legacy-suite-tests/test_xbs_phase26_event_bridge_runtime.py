from __future__ import annotations

import importlib.util
import json
import os
import stat
import subprocess
import sys
import concurrent.futures
import pytest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "apps/xbs/src/xbs_cli/event_bridge.py"


def load_module():
    spec = importlib.util.spec_from_file_location("xbs_event_bridge_under_test", MODULE)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def fixture(tmp_path: Path):
    event = tmp_path / "event.json"
    status = tmp_path / "status.json"
    key = tmp_path / "secure" / "key"
    envelope = tmp_path / "runtime" / "bridge.json"
    sequence = tmp_path / "runtime" / "bridge.seq"
    event.write_text(json.dumps({"safe_mode": False, "active": ["manual"], "inactive": []}))
    status.write_text(json.dumps({"mode": "normal", "active_profiles": ["manual"]}))
    return event, status, key, envelope, sequence


def test_publish_verify_and_permissions(tmp_path):
    bridge = load_module(); event, status, key, envelope, sequence = fixture(tmp_path)
    published = bridge.publish(event_path=event, status_path=status, key_path=key,
        envelope_path=envelope, sequence_path=sequence, now=1_800_000_000)
    verified = bridge.verify(key_path=key, envelope_path=envelope)
    assert published["sequence"] == 1
    assert verified["authenticated"] is True
    assert verified["payload"]["event"]["active"] == ["manual"]
    assert verified["envelope_digest"] == published["envelope_digest"]
    assert oct(key.stat().st_mode & 0o777) == "0o600"
    assert oct(envelope.stat().st_mode & 0o777) == "0o600"


def test_canonical_output_and_sequence_are_deterministic(tmp_path):
    bridge = load_module(); event, status, key, envelope, sequence = fixture(tmp_path)
    first = bridge.publish(event_path=event, status_path=status, key_path=key,
        envelope_path=envelope, sequence_path=sequence, now=1_800_000_000)
    raw = envelope.read_bytes()
    parsed = json.loads(raw)
    assert raw.rstrip() == bridge.canonical_json(parsed)
    second = bridge.publish(event_path=event, status_path=status, key_path=key,
        envelope_path=envelope, sequence_path=sequence, now=1_800_000_001)
    assert first["sequence"] == 1 and second["sequence"] == 2


def test_tampering_is_rejected(tmp_path):
    bridge = load_module(); event, status, key, envelope, sequence = fixture(tmp_path)
    bridge.publish(event_path=event, status_path=status, key_path=key,
        envelope_path=envelope, sequence_path=sequence, now=1_800_000_000)
    parsed = json.loads(envelope.read_text())
    parsed["payload"]["event"]["active"] = ["forged"]
    envelope.write_text(json.dumps(parsed))
    try:
        bridge.verify(key_path=key, envelope_path=envelope)
        assert False, "tampered envelope was accepted"
    except bridge.EventBridgeError as exc:
        assert exc.code == "bad_mac"


def test_invalid_or_oversized_sources_do_not_publish(tmp_path):
    bridge = load_module(); event, status, key, envelope, sequence = fixture(tmp_path)
    event.write_text("not-json")
    try:
        bridge.publish(event_path=event, status_path=status, key_path=key,
            envelope_path=envelope, sequence_path=sequence)
        assert False
    except bridge.EventBridgeError as exc:
        assert exc.code == "invalid_source_json"
    assert not envelope.exists()


def test_verify_does_not_create_missing_key_and_symlinks_are_rejected(tmp_path):
    bridge = load_module(); event, status, key, envelope, sequence = fixture(tmp_path)
    try:
        bridge.verify(key_path=key, envelope_path=envelope)
        assert False
    except bridge.EventBridgeError as exc:
        assert exc.code == "missing"
    assert not key.exists()
    target = tmp_path / "event-target.json"
    target.write_text("{}")
    event.unlink(); event.symlink_to(target)
    try:
        bridge.publish(event_path=event, status_path=status, key_path=key,
            envelope_path=envelope, sequence_path=sequence)
        assert False
    except bridge.EventBridgeError as exc:
        assert exc.code == "symlink_rejected"



def test_concurrent_publishers_allocate_unique_sequences(tmp_path):
    import concurrent.futures
    from xbs_cli import event_bridge
    root = tmp_path / "private"
    runtime = tmp_path / "runtime"
    root.mkdir(mode=0o700)
    runtime.mkdir(mode=0o700)
    event = runtime / "event.json"
    status = runtime / "status.json"
    event.write_text('{"active":[]}')
    status.write_text('{"mode":"active"}')
    key = root / "key"
    envelope = runtime / "envelope"
    sequence = runtime / "sequence"
    def one(_):
        return event_bridge.publish(event_path=event, status_path=status, key_path=key,
            envelope_path=envelope, sequence_path=sequence)["sequence"]
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        values = list(pool.map(one, range(40)))
    assert sorted(values) == list(range(1, 41))


def test_all_symlink_forms_are_rejected(tmp_path):
    from xbs_cli import event_bridge
    root = tmp_path / "private"
    runtime = tmp_path / "runtime"
    root.mkdir(mode=0o700)
    runtime.mkdir(mode=0o700)
    event = runtime / "event.json"
    status = runtime / "status.json"
    event.write_text('{}')
    status.write_text('{}')
    key = root / "key"
    envelope = runtime / "envelope"
    sequence = runtime / "sequence"
    event_bridge.publish(event_path=event, status_path=status, key_path=key,
        envelope_path=envelope, sequence_path=sequence)
    sequence.unlink()
    target = runtime / "seq-target"
    target.write_text('9')
    sequence.symlink_to(target)
    with pytest.raises(event_bridge.EventBridgeError) as exc:
        event_bridge.publish(event_path=event, status_path=status, key_path=key,
            envelope_path=envelope, sequence_path=sequence)
    assert exc.value.code == "symlink_rejected"
    sequence.unlink()
    empty = runtime / "empty"
    empty.write_text('')
    event.unlink()
    event.symlink_to(empty)
    with pytest.raises(event_bridge.EventBridgeError) as exc:
        event_bridge.publish(event_path=event, status_path=status, key_path=key,
            envelope_path=envelope, sequence_path=sequence)
    assert exc.value.code == "symlink_rejected"


def test_insecure_existing_key_is_rejected_not_repaired(tmp_path):
    from xbs_cli import event_bridge
    root = tmp_path / "private"
    runtime = tmp_path / "runtime"
    root.mkdir(mode=0o700)
    runtime.mkdir(mode=0o700)
    event = runtime / "event.json"
    status = runtime / "status.json"
    event.write_text('{}')
    status.write_text('{}')
    key = root / "key"
    key.write_bytes(b'x' * 32)
    key.chmod(0o644)
    with pytest.raises(event_bridge.EventBridgeError) as exc:
        event_bridge.publish(event_path=event, status_path=status, key_path=key,
            envelope_path=runtime / "envelope", sequence_path=runtime / "sequence")
    assert exc.value.code == "key_permissions"
    assert stat.S_IMODE(key.stat().st_mode) == 0o644



def test_concurrent_process_publishers_allocate_unique_sequences(tmp_path):
    root = tmp_path / "private"
    runtime = tmp_path / "runtime"
    root.mkdir(mode=0o700)
    runtime.mkdir(mode=0o700)
    event = runtime / "event.json"
    status = runtime / "status.json"
    event.write_text('{"active":[]}')
    status.write_text('{"mode":"active"}')
    key = root / "key"
    envelope = runtime / "envelope"
    sequence = runtime / "sequence"
    command = [
        sys.executable, str(MODULE), "publish",
        "--event", str(event), "--status", str(status),
        "--key", str(key), "--envelope", str(envelope),
        "--sequence", str(sequence),
    ]
    def one(_):
        result = subprocess.run(command, check=True, text=True, capture_output=True)
        return json.loads(result.stdout)["sequence"]
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        values = list(pool.map(one, range(32)))
    assert sorted(values) == list(range(1, 33))
