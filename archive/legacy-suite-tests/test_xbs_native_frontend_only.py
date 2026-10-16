from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
XBS = ROOT / "apps" / "xbs"


def test_xbs_web_assets_are_removed():
    assert not (XBS / "webroot").exists()
    assert not (XBS / "module" / "webroot").exists()
    assert not (XBS / "src" / "xbs_cli" / "assets" / "webroot").exists()
    assert not (XBS / "module" / "webui-selftest.sh").exists()


def test_xbs_cli_has_no_webui_command():
    text = (XBS / "src" / "xbs_cli" / "cli.py").read_text(encoding="utf-8")
    assert 'add_parser("webui")' not in text
    assert '("webui", "status")' not in text
    assert 'serve_webui' not in text
    assert 'open_webui' not in text


def test_xbs_android_catalog_has_no_webui_actions():
    text = (XBS / "android-app" / "app" / "src" / "main" / "java" / "io" / "github" / "mikeceead" / "xbscompanion" / "XbsCliCatalog.java").read_text(encoding="utf-8")
    assert 'webui_status' not in text
    assert 'webui_open' not in text
    assert 'webui_serve' not in text
