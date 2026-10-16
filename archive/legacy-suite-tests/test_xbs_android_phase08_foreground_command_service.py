from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
MANIFEST = ROOT / "apps/xbs/android-app/app/src/main/AndroidManifest.xml"


def read(path):
    return path.read_text(encoding="utf-8")


def test_root_command_service_is_manifested_as_non_exported_foreground_service():
    manifest = read(MANIFEST)
    assert 'android:name=".RootCommandService"' in manifest
    assert 'android:exported="false"' in manifest
    assert 'android:foregroundServiceType="specialUse"' in manifest
    assert 'Runs user-triggered XBS root commands with bounded timeouts' in manifest


def test_runner_starts_foreground_service_instead_of_private_executor():
    runner = read(SRC / "RootCommandRunner.java")
    assert "RootCommandService.start(app, runId, executionId, spec, taskerIntent)" in runner
    assert "ExecutorService" not in runner
    assert "EXECUTOR.execute" not in runner
    assert "static void execute(Context context, String runId" in runner
    assert "RunStore.cancelling(context, runId" in runner


def test_service_owns_foreground_lifecycle_and_bounded_workers():
    service = read(SRC / "RootCommandService.java")
    assert "extends Service" in service
    assert "ForegroundServiceLauncher.promote(this, FOREGROUND_ID" in service
    assert "Executors.newFixedThreadPool(2)" in service
    assert "RootCommandRunner.execute" in service
    assert "STOP_FOREGROUND_REMOVE" in service
    assert "ConcurrentHashMap<String, PendingCommand>" in service


def test_notification_cancellation_uses_context_aware_runner_path():
    receiver = read(SRC / "NotificationActionReceiver.java")
    assert "RootCommandService.requestCancel(context, runId)" in receiver
    controller = read(SRC / "NotificationController.java")
    assert "buildCommandServiceStatus" in controller
    assert "postCommandServiceStatus" in controller
    assert '"cancelling".equals(single.status)' in controller


def test_launch_result_and_unit_tests_cover_run_id_contract():
    result = read(SRC / "CommandLaunchResult.java")
    assert "final String runId" in result
    assert "this.runId = runId == null ? \"\" : runId" in result
    test = read(ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion/RootCommandServiceContractTest.java")
    assert "foregroundServiceUsesDedicatedNotificationId" in test
    assert "commandLaunchResultCarriesRunId" in test
