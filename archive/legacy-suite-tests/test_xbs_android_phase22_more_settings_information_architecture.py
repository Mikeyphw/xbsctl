from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"


def source(name: str) -> str:
    return (APP / name).read_text()


def test_settings_catalog_has_eight_non_overlapping_searchable_destinations():
    destination = source("SettingsDestination.java")
    catalog = source("SettingsCatalog.java")
    for token in [
        'RUNTIME = "runtime"', 'NOTIFICATIONS = "notifications"',
        'APPEARANCE = "appearance"', 'TASKER = "tasker"',
        'DATA = "data"', 'CONFIGURATION = "configuration"',
        'DIAGNOSTICS = "diagnostics"', 'ABOUT = "about"',
        "boolean matches(String query)", "static String boundedQuery", "MAX_QUERY_CHARS = 160",
    ]:
        assert token in destination
    assert catalog.count("new SettingsDestination(") == 8
    assert "Collections.unmodifiableList" in catalog
    assert "static List<SettingsDestination> filter" in catalog


def test_settings_tab_is_searchable_and_only_routes_to_detail_destinations():
    main = source("MainActivity.java")
    hub = source("SettingsHubView.java")
    assert "SettingsHubView.render(this,ui,c)" in main
    assert "Search settings, tools, and permissions" in hub
    assert "SettingsCatalog.filter(SettingsDestination.boundedQuery(query))" in hub
    assert "MoreSettingsActivity.open(activity, destination.id)" in hub
    for removed in ["Persistent XBS event monitor", "Clear all frontend caches", "Copy redacted support bundle"]:
        assert removed not in main


def test_detail_host_uses_dialogs_immediate_summaries_and_single_tool_ownership():
    activity = source("MoreSettingsActivity.java")
    for token in [
        "setSingleChoiceItems", "showPollDialog", "showResultModeDialog",
        "showToastModeDialog", "showRetentionDialog", "addToggle",
        "Request notification permission", "Active command progress",
        "Command results", "Runtime status", "Important alerts",
        "Palette preview", "Clear all frontend caches", "Configuration editor",
        "Open Root Diagnostics", "Copy redacted support bundle", "XBS Companion \" + BuildConfig.VERSION_NAME",
    ]:
        assert token in activity
    assert activity.count("Configuration editor") == 1
    assert activity.count("Clear all frontend caches") == 1
    assert activity.count("Copy redacted support bundle") == 1
    assert activity.count("Open Root Diagnostics") == 1


def test_release_gate_c_and_support_bundle_v9_are_sealed():
    build = (ROOT / "apps/xbs/android-app/app/build.gradle").read_text()
    support = source("SupportBundleBuilder.java")
    policy = source("ReleaseReadinessPolicy.java")
    manifest = (ROOT / "apps/xbs/android-app/app/src/main/AndroidManifest.xml").read_text()
    assert "xbsReleaseVersionCode = 15" in build
    assert "xbsReleaseVersionName = '4.2.0'" in build
    assert "MoreSettingsActivity.java" in build
    assert "FORMAT_VERSION = 18" in support
    assert "[Settings]" in support
    assert "settings_destination_ids=" in support
    assert "settingsDestinationIds()" in support
    assert "REQUIRED_SUPPORT_BUNDLE_FORMAT = 18" in policy
    assert "REQUIRED_VERSION_CODE = 15L" in policy
    assert '.MoreSettingsActivity' in manifest


def test_phase22_artifact_policy_is_disabled_when_manifest_is_present():
    manifest_path = ROOT / ".devtool-artifact.json"
    if not manifest_path.exists():
        return
    manifest = manifest_path.read_text()
    assert '"mode": "disabled"' in manifest
    assert '"failure_action": "keep"' in manifest
