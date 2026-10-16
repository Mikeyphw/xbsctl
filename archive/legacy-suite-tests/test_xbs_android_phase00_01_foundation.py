from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "apps" / "xbs" / "android-app"
JAVA = PROJECT / "app" / "src" / "main" / "java" / "io" / "github" / "mikeceead" / "xbscompanion"
MANIFEST = PROJECT / "app" / "src" / "main" / "AndroidManifest.xml"

ACTIVITIES = {
    "AppPickerActivity.java",
    "CliCommandActivity.java",
    "EventControlActivity.java",
    "MainActivity.java",
    "ProcessMonitorActivity.java",
    "TaskerEventEditActivity.java",
    "TaskerPluginActivity.java",
    "TaskerStateEditActivity.java",
    "XbsConfigActivity.java",
    "XbsEssentialsActivity.java",
    "XbsEventEditorActivity.java",
}


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def java(name: str) -> str:
    return read(JAVA / name)


def test_phase00_freezes_root_only_native_runtime_contract():
    runner = java("RootCommandRunner.java")
    config = java("XbsConfigApi.java")
    state = java("XbsStateProcessor.java")
    manifest = read(MANIFEST)

    assert 'new ProcessBuilder("su", "-c", shell)' in runner
    assert "static String shellQuote" in java("SuiteAction.java")
    assert "SuiteAction.shellQuote(event)" in java("MainActivity.java")
    assert "Built-in events cannot be deleted" in config
    assert "for (String name : current.active)" in state
    assert ".EventMonitorService" in manifest
    assert "com.twofortyfouram.locale.intent.action.FIRE_SETTING" in manifest
    assert "net.dinglisch.android.tasker.ACTION_EDIT_EVENT" in manifest


def test_phase00_forbids_termux_runtime_bridge_and_arbitrary_termux_permission():
    runtime_text = read(MANIFEST) + "\n" + "\n".join(
        read(path) for path in sorted(JAVA.glob("*.java"))
    )
    forbidden = (
        "com.termux.permission.RUN_COMMAND",
        "com.termux.RUN_COMMAND",
        "TermuxCommandRunner",
        "TermuxResultService",
        "TermuxPaths",
        "Termux access",
    )
    for token in forbidden:
        assert token not in runtime_text
    assert "Check root authorization and XBS module access" in java("ResultFormatter.java")


def test_phase00_preserves_all_current_native_frontend_activities():
    manifest = read(MANIFEST)
    for filename in ACTIVITIES:
        activity = filename.removesuffix(".java")
        assert f'.{activity}' in manifest
    assert "android.permission.QUERY_ALL_PACKAGES" in manifest
    assert "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in manifest


def test_phase01_enables_androidx_without_legacy_jetifier():
    properties = read(PROJECT / "gradle.properties")
    assert "android.useAndroidX=true" in properties
    assert "android.enableJetifier=false" in properties


def test_phase01_declares_material_lifecycle_recycler_and_room_foundation():
    build = read(PROJECT / "app" / "build.gradle")
    for dependency in (
        "androidx.activity:activity:",
        "androidx.appcompat:appcompat:",
        "androidx.lifecycle:lifecycle-runtime-android:",
        "androidx.lifecycle:lifecycle-viewmodel-android:",
        "androidx.recyclerview:recyclerview:",
        "androidx.savedstate:savedstate:",
        "androidx.room:room-runtime:",
        "androidx.room:room-compiler:",
        "com.google.android.material:material:",
    ):
        assert dependency in build
    assert "annotationProcessor 'androidx.room:room-compiler:" in build
    assert "androidx.compose" not in build


def test_phase01_uses_material_application_and_theme():
    manifest = read(MANIFEST)
    styles = read(PROJECT / "app" / "src" / "main" / "res" / "values" / "styles.xml")
    application = java("XbsApplication.java")

    ET.fromstring(manifest)
    ET.fromstring(styles)
    assert 'android:name=".XbsApplication"' in manifest
    assert 'android:theme="@style/Theme.XbsCompanion"' in manifest
    assert 'parent="Theme.Material3.Dark.NoActionBar"' in styles
    assert 'name="Theme.MikeControl" parent="Theme.XbsCompanion"' in styles
    assert "DynamicColors.applyToActivitiesIfAvailable(this)" in application
    assert "AppSettings.useMonet(this)" in application


def test_phase01_migrates_every_screen_to_shared_appcompat_base():
    base = java("XbsActivity.java")
    assert "extends AppCompatActivity" in base
    assert "WindowCompat.setDecorFitsSystemWindows(getWindow(), false)" in base
    assert "WindowInsetsCompat.Type.systemBars()" in base
    assert "ViewCompat.requestApplyInsets(root)" in base
    assert "super.setContentView(root)" in base

    for filename in ACTIVITIES:
        source = java(filename)
        assert "extends XbsActivity" in source
        assert "extends Activity" not in source
        assert "setXbsContentView(" in source
        assert "import android.app.Activity;" not in source


def test_phase01_uses_appcompat_dialogs_and_keeps_view_based_migration():
    combined = "\n".join(read(path) for path in sorted(JAVA.glob("*Activity.java")))
    assert "import android.app.AlertDialog;" not in combined
    assert "import androidx.appcompat.app.AlertDialog;" in combined
    assert "setContent {" not in combined
    assert "ComponentActivity" not in combined
