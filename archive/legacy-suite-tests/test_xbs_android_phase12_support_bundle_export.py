from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"


def read(rel):
    return (ROOT / rel).read_text()


def test_support_bundle_builder_is_bounded_and_text_only():
    src = (APP / "SupportBundleBuilder.java").read_text()
    assert "final class SupportBundleBuilder" in src
    assert "MAX_RUNS = 24" in src
    assert "MAX_EVENTS = 12" in src
    assert "MAX_FIELD_CHARS = 700" in src
    assert "appendOutput" in src
    assert "_tail=" in src
    assert "getExternalStorage" not in src
    assert "FileOutputStream" not in src


def test_settings_exposes_copy_and_share_without_storage_permissions():
    src = (APP / "MainActivity.java").read_text()
    assert "copySupportBundle" in src
    assert "shareSupportBundle" in src
    assert "SupportBundleBuilder.build(this)" in src
    assert "ClipData.newPlainText(\"XBS support bundle\"" in src
    assert "IntentSecurityPolicy.textShare" in src
    assert "Intent.ACTION_SEND" in (APP / "IntentSecurityPolicy.java").read_text()
    manifest = read("apps/xbs/android-app/app/src/main/AndroidManifest.xml")
    assert "WRITE_EXTERNAL_STORAGE" not in manifest
    assert "READ_EXTERNAL_STORAGE" not in manifest


def test_phase12_docs_and_android_unit_contract_are_present():
    assert "Phase 12: support bundle export" in read("apps/xbs/android-app/PHASE_12_SUPPORT_BUNDLE_EXPORT.md")
    assert "Phase 12: Support bundle export" in read("apps/xbs/android-app/README.md")
    assert "Phase 12 validation" in read("apps/xbs/android-app/VALIDATION.md")
    test_src = read("apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion/SupportBundleBuilderContractTest.java")
    assert "bundleLimitsRemainBounded" in test_src
