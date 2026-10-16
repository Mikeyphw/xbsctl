from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TESTS = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"


def source(name: str) -> str:
    return (APP / name).read_text()


def test_suite_actions_reject_forged_known_catalog_ids_and_unsafe_arguments():
    spec = source("XbsCommandSpec.java")
    action = source("SuiteAction.java")
    junit = (TESTS / "XbsCommandPolicyTest.java").read_text()
    assert "Suite catalog action instance does not match the allowlist" in spec
    assert "SuiteCatalog.find(action.module, action.id)" in spec
    assert "SAFE_ARGUMENT_PATTERN" in action
    assert "MAX_ARGUMENT_CHARS = 96" in action
    assert "Action argument must fit on one shell token line" in action
    assert "policyRejectsForgedSuiteCatalogActionObjects" in junit


def test_command_center_rechecks_availability_and_bounds_fields_before_launch():
    runner = source("RootCommandRunner.java")
    field = source("CliField.java")
    activity = source("CliCommandActivity.java")
    assert "CommandCenterPolicy.describe(" in runner
    assert "descriptor.availability == CommandCenterPolicy.Availability.BLOCKED" in runner
    assert "SAFE_EVENT_PATTERN" in field
    assert "SAFE_DOTTED_KEY_PATTERN" in field
    assert "fieldMax()" in field
    assert "MAX_SEARCH_CHARS = 160" in activity
    assert "MAX_SHOWN_COMMANDS = 120" in activity
    assert "boundedSearch(" in activity


def test_settings_search_and_support_bundle_are_bounded():
    destination = source("SettingsDestination.java")
    hub = source("SettingsHubView.java")
    support = source("SupportBundleBuilder.java")
    junit = (TESTS / "SettingsCatalogTest.java").read_text()
    assert "MAX_QUERY_CHARS = 160" in destination
    assert "MAX_QUERY_TOKENS = 12" in destination
    assert "QUERY_OVERSIZED" in destination
    assert "static String boundedQuery" in destination
    assert "SettingsDestination.boundedQuery" in hub
    assert "MAX_BUNDLE_CHARS = 128000" in support
    assert "settings_destinations=" in support
    assert "settings_destination_ids=" in support
    assert "settingsDestinationIds()" in support
    assert "support bundle truncated" in support
    assert "oversizedSearchQueriesFailClosedForDirectFilters" in junit


def test_quick_actions_recheck_diagnostics_and_capabilities_at_launch():
    sheet = source("QuickActionsSheet.java")
    assert "RootDiagnosticsStore.cached(activity)" in sheet
    assert "CommandCenterPolicy.describe(" in sheet
    assert "descriptor.availability == CommandCenterPolicy.Availability.BLOCKED" in sheet
    assert "capabilities.report" in sheet
