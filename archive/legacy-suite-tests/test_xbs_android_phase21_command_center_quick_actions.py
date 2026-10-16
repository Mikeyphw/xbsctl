from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"


def source(name: str) -> str:
    return (APP / name).read_text()


def test_command_center_uses_allowlisted_typed_specs_and_immutable_preview():
    activity = source("CliCommandActivity.java")
    spec = source("XbsCommandSpec.java")
    runner = source("RootCommandRunner.java")
    assert "RootCommandRunner.runCatalog" in activity
    assert "XbsCommandSpec.fromCatalog" in activity
    assert "Generated command" in activity
    assert "cannot be edited" in activity
    assert "RootCommandRunner.runCommand(this,cmd.id" not in activity
    assert "static XbsCommandSpec fromCatalog" in spec
    assert "Catalog command instance does not match the allowlist" in spec
    assert "static CommandLaunchResult runCatalog" in runner


def test_catalog_exposes_risk_effect_timeout_and_compatibility_guidance():
    policy = source("CommandCenterPolicy.java")
    activity = source("CliCommandActivity.java")
    for token in [
        "enum Availability", "enum Risk", "expectedEffect", "timeoutSeconds",
        "config_set", "config_edit_cli", "completion_install", "manpage_install",
        "Run Root Diagnostics", "native Configuration editor",
    ]:
        assert token in policy
    for token in ["riskLabel()", "Expected effect:", "timeout", "showCompatibility"]:
        assert token in activity


def test_destructive_actions_require_explicit_confirmation():
    activity = source("CliCommandActivity.java")
    assert "confirmDestructive" in activity
    assert "Run destructive action" in activity
    assert "descriptor.confirmationRequired" in activity


def test_quick_actions_are_typed_and_include_required_controls():
    sheet = source("QuickActionsSheet.java")
    main = source("MainActivity.java")
    for token in [
        "Validate configuration", "Reload runtime", "Pause runtime", "Resume runtime",
        "Enter safe mode", "Exit safe mode", "Root Diagnostics", "Command Center",
        "Start manual event", "Stop manual event", "EventControlActivity.class",
        "SuiteCatalog.find", "RootCommandRunner.run",
    ]:
        assert token in sheet
    assert "RootCommandRunner.runCommand" not in sheet
    assert "CommandCenterPolicy.describe(" in sheet
    assert "RootDiagnosticsStore.cached(activity)" in sheet
    assert "QuickActionsSheet.show(this)" in main


def test_phase21_keeps_validation_disabled_and_forbids_termux_surface():
    manifest = (ROOT / ".devtool-artifact.json").read_text() if (ROOT / ".devtool-artifact.json").exists() else ""
    combined = "\n".join(path.read_text() for path in APP.glob("*.java"))
    assert "com.termux.permission.RUN_COMMAND" not in combined
    assert "com.termux.RUN_COMMAND" not in combined
    assert "TermuxCommandRunner" not in combined
    if manifest:
        assert '"mode": "disabled"' in manifest
        assert '"failure_action": "keep"' in manifest
