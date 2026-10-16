from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "apps" / "xbs" / "android-app"
JAVA = PROJECT / "app" / "src" / "main" / "java" / "io" / "github" / "mikeceead" / "xbscompanion"
MANIFEST = PROJECT / "app" / "src" / "main" / "AndroidManifest.xml"
RULES = PROJECT / "app" / "src" / "main" / "res" / "xml" / "xbs_data_extraction_rules.xml"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def java(name: str) -> str:
    return read(JAVA / name)


def test_phase03_external_storage_paths_are_runtime_derived():
    config = java("XbsConfigApi.java")
    activity = java("XbsConfigActivity.java")

    assert "import android.os.Environment;" in config
    assert "Environment.getExternalStorageDirectory().getPath()" in config
    assert "static final String CONFIG = EXTERNAL_STORAGE_ROOT + \"/.config/xbs/XtremeBS.json\";" in config
    assert "static final String LIST_DIR = EXTERNAL_STORAGE_ROOT + \"/.config/xbs/lists\";" in config
    assert "static final String BACKUP_DIR = EXTERNAL_STORAGE_ROOT + \"/.config/xbs/backups\";" in config
    assert "XbsConfigApi.LIST_DIR + \"/essentials.allowlist\"" in activity

    runtime_sources = "\n".join(java(name) for name in ("XbsConfigApi.java", "XbsConfigActivity.java"))
    assert '"/sdcard' not in runtime_sources


def test_phase03_backup_directory_uses_canonical_runtime_constant():
    config = java("XbsConfigApi.java")
    transaction = java("XbsConfigTransaction.java")

    assert "XbsConfigApi.q(XbsConfigApi.LIST_DIR)" in transaction
    assert "XbsConfigApi.q(XbsConfigApi.BACKUP_DIR)" in transaction
    assert 'TRANSACTION_DIR = XbsConfigApi.BACKUP_DIR + "/transactions"' in transaction
    assert 'q("/sdcard/.config/xbs/backups' not in config
    assert 'LIST_DIR + "/essentials.allowlist"' in config


def test_phase03_declares_android_12_data_extraction_rules():
    manifest = read(MANIFEST)
    rules = read(RULES)

    ET.fromstring(manifest)
    ET.fromstring(rules)
    assert 'android:allowBackup="false"' in manifest
    assert 'android:dataExtractionRules="@xml/xbs_data_extraction_rules"' in manifest
    assert "<cloud-backup" in rules
    assert "<device-transfer>" in rules
    assert 'domain="root" path="."' in rules
    assert 'domain="external" path="."' in rules
