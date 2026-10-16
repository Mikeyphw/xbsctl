from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps" / "xbs" / "android-app" / "app" / "src" / "main"
RES = APP / "res"


def read(rel: str) -> str:
    return (ROOT / rel).read_text()


def test_legacy_launcher_warning_tail_is_removed():
    assert not (RES / "drawable" / "ic_launcher_legacy.xml").exists()


def test_stale_anydpi_v26_folder_is_scoped_in_lint_config():
    lint = read("apps/xbs/android-app/app/lint.xml")
    assert '<issue id="ObsoleteSdkInt">' in lint
    assert 'src/main/res/mipmap-anydpi-v26' in lint
    assert 'exact stale resource folder' in lint


def test_manifest_launcher_resources_stay_linkable():
    manifest = read("apps/xbs/android-app/app/src/main/AndroidManifest.xml")
    assert 'android:icon="@mipmap/ic_launcher"' in manifest
    assert 'android:roundIcon="@mipmap/ic_launcher_round"' in manifest
    for rel in [
        "apps/xbs/android-app/app/src/main/res/mipmap/ic_launcher.xml",
        "apps/xbs/android-app/app/src/main/res/mipmap/ic_launcher_round.xml",
    ]:
        icon = read(rel)
        assert "<adaptive-icon" in icon
        assert '<monochrome android:drawable="@drawable/ic_devtool_notification" />' in icon


def test_no_code_or_resource_references_legacy_launcher_drawable():
    for path in (ROOT / "apps" / "xbs" / "android-app" / "app" / "src" / "main").rglob("*"):
        if path.is_file() and path.suffix in {".java", ".xml"}:
            text = path.read_text()
            assert "ic_launcher_legacy" not in text
