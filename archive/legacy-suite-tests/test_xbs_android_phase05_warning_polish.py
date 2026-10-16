from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps" / "xbs" / "android-app" / "app" / "src" / "main"


def read(rel: str) -> str:
    return (ROOT / rel).read_text()


def test_boot_receiver_filters_boot_completed_action():
    src = read("apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion/BootReceiver.java")
    assert "Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())" in src
    assert "intent == null" in src


def test_tasker_exported_receivers_are_justified_not_permission_broken():
    manifest = read("apps/xbs/android-app/app/src/main/AndroidManifest.xml")
    assert 'android:name=".TaskerPluginReceiver"' in manifest
    assert 'android:name=".TaskerConditionReceiver"' in manifest
    assert 'tools:ignore="ExportedReceiver"' in manifest
    assert "Exported by Tasker" in manifest
    assert 'android:permission=' not in manifest


def test_summary_counts_use_plurals():
    strings = read("apps/xbs/android-app/app/src/main/res/values/strings.xml")
    assert '<plurals name="xbs_cached_apps">' in strings
    assert '<plurals name="xbs_selected_apps">' in strings
    assert '<plurals name="xbs_shown_processes">' in strings
    assert '<string name="xbs_app_picker_summary">%1$s · %2$s · %3$s%4$s</string>' in strings
    app_picker = read("apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion/AppPickerActivity.java")
    process = read("apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion/ProcessMonitorActivity.java")
    assert "getQuantityString(R.plurals.xbs_cached_apps" in app_picker
    assert "getQuantityString(R.plurals.xbs_shown_processes" in process


def test_launcher_icons_are_unqualified_mipmap_and_monochrome():
    assert not (APP / "res" / "mipmap-anydpi-v26" / "ic_launcher.xml").exists()
    assert not (APP / "res" / "mipmap-anydpi-v26" / "ic_launcher_round.xml").exists()
    assert not (APP / "res" / "mipmap-anydpi" / "ic_launcher.xml").exists()
    assert not (APP / "res" / "mipmap-anydpi" / "ic_launcher_round.xml").exists()
    assert not (APP / "res" / "mipmap-anydpi-v26").exists()
    assert not (APP / "res" / "drawable" / "ic_launcher_legacy.xml").exists()
    for name in ["ic_launcher.xml", "ic_launcher_round.xml"]:
        icon = (APP / "res" / "mipmap" / name).read_text()
        assert "<adaptive-icon" in icon
        assert '<monochrome android:drawable="@drawable/ic_devtool_notification" />' in icon


def test_unused_warning_resources_removed_or_reused():
    colors = read("apps/xbs/android-app/app/src/main/res/values/colors.xml")
    styles = read("apps/xbs/android-app/app/src/main/res/values/styles.xml")
    strings = read("apps/xbs/android-app/app/src/main/res/values/strings.xml")
    manifest = read("apps/xbs/android-app/app/src/main/AndroidManifest.xml")
    notification = read("apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion/NotificationController.java")
    assert "devtool_muted" not in colors
    assert "devtool_yellow" not in colors
    assert 'name="Theme.MikeControl" parent="Theme.XbsCompanion" tools:ignore="UnusedResources"' in styles
    assert 'android:label="@string/tasker_action_name"' in manifest
    assert 'R.string.notification_channel_name' in notification
    assert '.getString(R.string.notification_channel_name)' in notification
    for rel in [
        "apps/xbs/android-app/app/src/main/res/drawable/ic_done.xml",
        "apps/xbs/android-app/app/src/main/res/drawable/ic_start.xml",
        "apps/xbs/android-app/app/src/main/res/drawable/ic_tracker.xml",
    ]:
        assert not (ROOT / rel).exists()
    assert "notification_runtime_channel_name" in strings


def test_dynamic_color_reflection_is_scoped_suppressed():
    src = read("apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion/AppPalette.java")
    assert 'import android.annotation.SuppressLint;' in src
    assert '@SuppressLint("DiscouragedApi")' in src
    assert 'getIdentifier(name,"color","android")' in src
