from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TESTS = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"


def text(path: Path) -> str:
    return path.read_text()


def test_phase32b_event_bridge_parser_demotes_cli_usage_to_availability_fallback():
    envelope = text(JAVA / "EventBridgeEnvelope.java")
    policy = text(JAVA / "EventBridgePolicy.java")
    junit = text(TESTS / "EventBridgeEnvelopeTest.java")
    assert 'looksCliUsage(machine)' in envelope
    assert 'failure("verifier_usage"' in envelope
    assert 'failure("invalid_machine_output"' in envelope
    assert '"verifier_usage".equals(value)' in policy
    assert 'randomNonJsonMachineOutputStillFailsClosed' in junit
    assert 'cliUsageMachineOutputIsAvailabilityFallbackNotSecurityFailure' in junit


def test_phase32b_verified_command_prefers_module_binary_and_json_wraps_legacy_usage():
    reader = text(JAVA / "EventBridgeReader.java")
    resolver = text(JAVA / "XbsRuntimeResolver.java")
    assert 'XbsRuntimeResolver.probeCalls("run_xbs_bridge", "xbs")' in reader
    assert resolver.index('/data/adb/modules/') < resolver.index('/system/bin')
    assert '/data/adb/modules_update/' in resolver
    assert '/data/adb/ksu/modules/' in resolver
    assert 'case' in reader and '$OUT' in reader and '\\{*)' in reader
    assert 'reason_code' in reader and 'verifier_usage' in reader
    assert 'adaptive fallback will poll legacy runtime state' in reader
    assert 'reason_code' in reader and 'xbs_missing' in reader
