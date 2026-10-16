from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "apps" / "xbs" / "android-app"
JAVA = PROJECT / "app" / "src" / "main" / "java" / "io" / "github" / "mikeceead" / "xbscompanion"
MANIFEST = PROJECT / "app" / "src" / "main" / "AndroidManifest.xml"
STRINGS = PROJECT / "app" / "src" / "main" / "res" / "values" / "strings.xml"
FULL_BACKUP = PROJECT / "app" / "src" / "main" / "res" / "xml" / "xbs_full_backup_content.xml"

SWITCH_SOURCES = (
    "AppPickerActivity.java",
    "CliCommandActivity.java",
    "MainActivity.java",
    "TaskerPluginActivity.java",
    "UiKit.java",
    "XbsConfigActivity.java",
    "XbsEventEditorActivity.java",
)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def java(name: str) -> str:
    return read(JAVA / name)


def test_phase04_uses_appcompat_switches_for_programmatic_toggles():
    combined = "\n".join(java(name) for name in SWITCH_SOURCES)

    assert "SwitchCompat toggle(String label,boolean checked)" in java("UiKit.java")
    assert "new SwitchCompat(context)" in java("UiKit.java")
    assert "androidx.appcompat.widget.SwitchCompat" in combined
    assert "import android.widget.Switch;" not in combined
    assert "new Switch(" not in combined
    assert "instanceof Switch)" not in combined
    assert "android.widget.Switch" not in combined

    for filename in (
        "AppPickerActivity.java",
        "CliCommandActivity.java",
        "MainActivity.java",
        "TaskerPluginActivity.java",
        "XbsConfigActivity.java",
        "XbsEventEditorActivity.java",
    ):
        assert "SwitchCompat" in java(filename)


def test_phase04_moves_reported_settext_compositions_to_resources():
    strings = read(STRINGS)
    ET.fromstring(strings)
    for name in (
        "xbs_nav_button_label",
        "xbs_status_line",
        "xbs_app_picker_summary",
        "xbs_app_picker_summary_detail",
        "xbs_process_summary",
        "xbs_read_only_path",
        "xbs_read_only_path_empty",
        "xbs_saving",
        "xbs_validate_save_template",
        "xbs_validate_save_event",
        "xbs_sort_cpu",
        "xbs_sort_ram",
        "xbs_sort_name",
    ):
        assert f'name="{name}"' in strings

    assert "context.getString(R.string.xbs_nav_button_label, symbol, label)" in java("UiKit.java")
    assert "getString(R.string.xbs_status_line, label, value)" in java("MainActivity.java")
    app_picker = java("AppPickerActivity.java")
    process_monitor = java("ProcessMonitorActivity.java")
    assert "getString(R.string.xbs_app_picker_summary" in app_picker
    assert "getQuantityString(R.plurals.xbs_cached_apps" in app_picker
    assert "getString(R.string.xbs_process_summary" in process_monitor
    assert "getQuantityString(R.plurals.xbs_shown_processes" in process_monitor
    assert "saveButton.setText(R.string.xbs_saving)" in java("XbsEventEditorActivity.java")
    assert "saveButton.setText(template?R.string.xbs_validate_save_template:R.string.xbs_validate_save_event)" in java("XbsEventEditorActivity.java")

    assert 'setText(symbol+"\\n"+label)' not in java("UiKit.java")
    assert 'summary.setText(apps.size() + " cached apps' not in java("AppPickerActivity.java")
    assert 'summary.setText(shown+" shown' not in java("ProcessMonitorActivity.java")
    assert 'saveButton.setText("Saving…")' not in java("XbsEventEditorActivity.java")


def test_phase04_declares_pre_android_12_backup_compatibility_rules():
    manifest = read(MANIFEST)
    backup = read(FULL_BACKUP)
    ET.fromstring(manifest)
    ET.fromstring(backup)

    assert 'android:dataExtractionRules="@xml/xbs_data_extraction_rules"' in manifest
    assert 'android:fullBackupContent="@xml/xbs_full_backup_content"' in manifest
    assert "<full-backup-content>" in backup
    assert 'domain="root" path="."' in backup
    assert 'domain="external" path="."' in backup
