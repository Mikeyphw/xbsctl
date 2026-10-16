from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"

def read(name):
    return (SRC / name).read_text()

def test_template_is_not_a_runtime_event():
    snapshot = read("XbsSnapshot.java")
    assert '"template".equals(name)' in snapshot
    assert "runtimeEvents(active)" in snapshot
    assert "runtimeEvents(inactive)" in snapshot

def test_template_editor_requires_explicit_route():
    editor = read("XbsEventEditorActivity.java")
    assert "EXTRA_TEMPLATE_DEFAULTS" in editor
    assert 'templateDefaults&&"template".equals(originalName)' in editor
    assert "Event not found:" in editor

def test_normal_event_editors_do_not_route_template_implicitly():
    main = read("MainActivity.java")
    config = read("XbsConfigActivity.java")
    assert "openTemplateEditor()" in main
    assert "openTemplateEvent()" in config
    assert "putExtra(XbsEventEditorActivity.EXTRA_TEMPLATE_DEFAULTS,true)" in main
    assert "putExtra(XbsEventEditorActivity.EXTRA_TEMPLATE_DEFAULTS,true)" in config
