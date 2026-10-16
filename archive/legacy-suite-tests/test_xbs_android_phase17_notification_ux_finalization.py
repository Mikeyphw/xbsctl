from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TEST_JAVA = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_active_commands_use_one_consolidated_foreground_notification():
    controller = read(JAVA / "NotificationController.java")
    runner = read(JAVA / "RootCommandRunner.java")
    service = read(JAVA / "RootCommandService.java")
    assert "Posts only terminal per-run results" in controller
    assert "NotificationUxPolicy.isActive(record.status)" in controller
    assert "new NotificationController(app).post(record)" not in runner
    assert "buildCommandServiceStatus(RunStore.active(this), detail)" in service
    assert "setOnlyAlertOnce(true)" in controller


def test_command_completion_policy_defaults_to_failures_only_and_is_user_selectable():
    settings = read(JAVA / "AppSettings.java")
    settings_ui = read(JAVA / "MoreSettingsActivity.java")
    policy = read(JAVA / "NotificationUxPolicy.java")
    assert "RESULTS_FAILURES_ONLY" in settings
    assert "commandResultNotificationModeLabel" in settings
    assert "Command completion alerts" in settings_ui
    assert "showResultModeDialog" in settings_ui
    assert "shouldPostTerminal" in policy
    assert "exitCode == 124" in policy and "exitCode == 130" in policy


def test_notification_channels_are_split_and_individually_repairable():
    controller = read(JAVA / "NotificationController.java")
    diagnostics = read(JAVA / "RootDiagnosticsActivity.java")
    for channel in (
        "CHANNEL_COMMAND_PROGRESS",
        "CHANNEL_COMMAND_RESULTS",
        "CHANNEL_STATUS",
        "CHANNEL_ALERTS",
    ):
        assert channel in controller
        assert channel in diagnostics
    assert "ACTION_CHANNEL_NOTIFICATION_SETTINGS" in controller
    assert "commandProgressChannelReady" in controller
    assert "commandResultsChannelReady" in controller
    assert 'deleteNotificationChannel(OLD_CHANNEL_RUNS)' in controller


def test_runtime_actions_reject_stale_state_and_duplicate_taps():
    receiver = read(JAVA / "NotificationActionReceiver.java")
    policy = read(JAVA / "NotificationUxPolicy.java")
    assert "ACTION_DEDUPE_WINDOW_MS" in policy
    assert "RuntimeNotificationPolicy.evaluateAction" in receiver
    assert "RECENT_ACTIONS" in receiver
    assert '"event_stop".equals(actionId)' in policy
    assert "activeEvents.contains(event)" in policy


def test_dynamic_notification_ids_are_namespaced_and_deep_links_are_precise():
    ids = read(JAVA / "NotificationIds.java")
    controller = read(JAVA / "NotificationController.java")
    assert "RUN_PREFIX" in ids and "EVENT_PREFIX" in ids and "REQUEST_PREFIX" in ids
    assert 'putExtra("run_id", runId)' in controller
    assert "RootDiagnosticsActivity.class" in controller
    assert "NotificationIds.run(record.runId)" in controller
    assert "NotificationIds.event(event.id)" in controller


def test_support_bundle_exports_notification_readiness_and_policy():
    support = read(JAVA / "SupportBundleBuilder.java")
    assert "FORMAT_VERSION = 18" in support
    assert "[Notifications]" in support
    assert "command_progress_channel=" in support
    assert "command_results_channel=" in support
    assert "active_command_model=single_consolidated_foreground_notification" in support


def test_java_regressions_cover_policy_actions_and_id_namespaces():
    test = read(TEST_JAVA / "NotificationUxPolicyTest.java")
    for marker in (
        "activeRunsNeverCreateDuplicatePerRunNotifications",
        "failuresOnlyIncludesFailureTimeoutAndCancellation",
        "staleRuntimeActionsAreRejected",
        "duplicateNotificationActionsAreDebounced",
        "dynamicIdNamespacesAreStableAndSeparated",
    ):
        assert marker in test


def test_phase17_has_no_android_resource_or_manifest_churn():
    assert not (ROOT / "apps/xbs/android-app/app/src/main/res/layout/phase17.xml").exists()
    manifest = read(ROOT / "apps/xbs/android-app/app/src/main/AndroidManifest.xml")
    assert '<receiver android:name=".NotificationActionReceiver" android:exported="false" />' in manifest
