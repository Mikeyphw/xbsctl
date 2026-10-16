from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "apps" / "xbs" / "android-app"
JAVA = PROJECT / "app" / "src" / "main" / "java" / "io" / "github" / "mikeceead" / "xbscompanion"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def java(name: str) -> str:
    return read(JAVA / name)


def test_phase02_case_normalization_uses_locale_root():
    expected = {
        "MachineResult.java": "s.toLowerCase(Locale.ROOT)",
        "RunDetailFormatter.java": "safe(record.status).toUpperCase(Locale.ROOT)",
        "ResultFormatter.java": "toLowerCase(Locale.ROOT)",
        "XbsEvent.java": "pretty(state).toLowerCase(Locale.ROOT)",
    }
    for filename, token in expected.items():
        source = java(filename)
        assert "import java.util.Locale;" in source
        assert token in source

    combined = "\n".join(java(name) for name in expected)
    forbidden = (
        "toLowerCase();",
        "toUpperCase();",
        "toLowerCase().replaceAll",
        "toUpperCase()+",
        "text.toLowerCase();",
        "line.toLowerCase();",
        "value.toLowerCase();",
        "pretty(state).toLowerCase();",
    )
    for token in forbidden:
        assert token not in combined


def test_phase02_removes_obsolete_min_sdk_26_fallbacks_only():
    event_monitor = java("EventMonitorService.java")
    notification = java("NotificationController.java")
    tasker = java("TaskerProtocol.java")

    assert "import android.os.Build;" not in event_monitor
    assert 'ForegroundServiceLauncher.start(context, intent, "event_monitor_start")' in event_monitor
    assert "context.startService(intent);" not in event_monitor
    assert "Build.VERSION.SDK_INT >= 26" not in event_monitor

    assert "NotificationChannel progress = new NotificationChannel" in notification
    assert "NotificationChannel results = new NotificationChannel" in notification
    assert "new Notification.Builder(context, channel)" in notification
    assert "Build.VERSION.SDK_INT >= 26" not in notification
    assert "Build.VERSION.SDK_INT < 33" in notification
    assert "Build.VERSION.SDK_INT >= 31" in notification

    assert "import android.os.Build;" not in tasker
    assert 'ForegroundServiceLauncher.start(context,done,"tasker_completion")' in tasker
    assert "context.startService(done)" in tasker
    assert "Build.VERSION.SDK_INT>=26" not in tasker
