from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"


def read(path):
    return (ROOT / path).read_text()


def test_diagnostics_workbench_covers_root_binary_storage_and_android_readiness():
    activity = (APP / "RootDiagnosticsActivity.java").read_text()
    probe = (APP / "RootDiagnosticsProbe.java").read_text()
    report = (APP / "RootDiagnosticsReport.java").read_text()
    assert "Dedicated root, module, storage, notification, and service diagnostics workbench" in activity
    assert "Run full diagnostics" in activity
    assert "Repair XBS storage paths" in activity
    assert "probe_dir" in probe
    assert "manager_version" in probe
    assert "xbs_executable" in probe and "xbsctl_executable" in probe
    assert r'case \"$VERSION\" in' in probe
    assert "notificationPermissionGranted" in report
    assert "commandServiceActive" in report
    assert "monitorServiceActive" in report


def test_guided_repairs_are_narrow_and_do_not_broadly_change_permissions():
    probe = (APP / "RootDiagnosticsProbe.java").read_text()
    assert "mkdir -p" in probe
    assert "--json init" in probe
    assert "chmod 777" not in probe
    assert "chmod -R" not in probe
    assert "rm -rf" not in probe
    assert "WRITE_EXTERNAL_STORAGE" not in read("apps/xbs/android-app/app/src/main/AndroidManifest.xml")


def test_diagnostics_are_reachable_and_exported_in_support_bundle():
    settings = (APP / "MoreSettingsActivity.java").read_text()
    support = (APP / "SupportBundleBuilder.java").read_text()
    manifest = read("apps/xbs/android-app/app/src/main/AndroidManifest.xml")
    assert "Open Root Diagnostics" in settings
    assert "RootDiagnosticsActivity.class" in settings
    assert ".RootDiagnosticsActivity" in manifest
    assert "[Diagnostics]" in support
    assert "RootDiagnosticsStore.cached" in support
    assert "FORMAT_VERSION = 18" in support


def test_service_state_and_channel_readiness_are_observable():
    command_service = (APP / "RootCommandService.java").read_text()
    monitor_service = (APP / "EventMonitorService.java").read_text()
    notifications = (APP / "NotificationController.java").read_text()
    assert "SERVICE_ACTIVE" in command_service and "isServiceActive" in command_service
    assert "SERVICE_ACTIVE" in monitor_service and "isServiceActive" in monitor_service
    assert "static Readiness readiness" in notifications
    assert "IMPORTANCE_NONE" in notifications


def test_root_diagnostics_uses_the_real_root_status_message_contract():
    probe = (APP / "RootDiagnosticsProbe.java").read_text()
    root_status = (APP / "RootStatus.java").read_text()
    assert "final String message;" in root_status
    assert "clean(root.message)" in probe
    assert "root.error" not in probe


def test_foreground_service_permission_probe_is_min_sdk_lint_safe():
    probe = (APP / "RootDiagnosticsProbe.java").read_text()
    assert 'FOREGROUND_SERVICE_PERMISSION = "android.permission.FOREGROUND_SERVICE"' in probe
    assert "Manifest.permission.FOREGROUND_SERVICE" not in probe
