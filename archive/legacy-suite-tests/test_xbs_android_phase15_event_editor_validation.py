from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TEST_JAVA = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"


def java(name: str) -> str:
    return (JAVA / name).read_text()


def test_event_editor_uses_reusable_draft_validator_and_inline_errors():
    validator = java("XbsEventDraftValidator.java")
    editor = java("XbsEventEditorActivity.java")
    assert "final class XbsEventDraftValidator" in validator
    assert "static Result validate(Draft draft)" in validator
    assert "firstError(String field)" in validator
    assert "validationSummary" in editor
    assert "addTextChangedListener(watcher)" in editor
    assert "field.setError(error)" in editor
    assert "setSaveEnabled(!result.hasErrors())" in editor


def test_identity_package_and_conflict_validation_are_explicit():
    validator = java("XbsEventDraftValidator.java")
    for token in (
        "Built-in event names are reserved",
        "Use only letters, numbers, dots, underscores, and hyphens",
        "Invalid Android package name",
        "Duplicate entry",
        "is present in both allowlist and denylist",
        "allowlist wins at runtime",
    ):
        assert token in validator


def test_process_rules_validate_shape_shell_safety_and_nice_range():
    validator = java("XbsEventDraftValidator.java")
    for token in (
        "Use one process pattern and an optional nice value",
        "Process pattern contains unsafe shell punctuation",
        "Linux nice value must be between -20 and 19",
        "Nice value must be an integer between -20 and 19",
        "Process handling is enabled, but no process rules are defined",
    ):
        assert token in validator
    assert "containsShellMeta(process)" in validator


def test_cpu_validation_rejects_bad_names_and_contradictory_actions():
    validator = java("XbsEventDraftValidator.java")
    assert 'token.matches("cpu[0-9]+")' in validator
    assert "cannot be both governor-managed and disabled" in validator
    assert "Disabling cpu0" in validator
    assert "Duplicate CPU core" in validator


def test_warnings_are_reviewed_before_transactional_save():
    editor = java("XbsEventEditorActivity.java")
    assert 'setTitle("Review event warnings")' in editor
    assert "result.warningText(8)" in editor
    assert "confirmReplacementOrSave(state)" in editor
    assert "XbsConfigApi.save(this,workingConfig,files,true)" in editor
    assert "Phase 14 journal with rollback protection" in editor


def test_background_save_uses_immutable_capture_not_live_widgets():
    editor = java("XbsEventEditorActivity.java")
    assert "EditorState state=captureState()" in editor
    assert "JSONObject workingConfig=new JSONObject(config.toString())" in editor
    start = editor.index("executor.execute(()->{", editor.index("private void performSave"))
    end = editor.index("    private void putBehavior", start)
    background = editor[start:end]
    for forbidden in ("allow.getText", "deny.getText", "proc.getText", ".isChecked()", "choiceValue("):
        assert forbidden not in background


def test_phase15_java_regressions_cover_validation_matrix():
    test = (TEST_JAVA / "XbsEventDraftValidatorTest.java").read_text()
    for method in (
        "acceptsWellFormedEventDraft",
        "rejectsReservedOrMalformedIdentity",
        "detectsPackageErrorsDuplicatesAndAllowDenyConflicts",
        "validatesProcessRuleShapeSafetyAndNiceRange",
        "processHandlingRequiresRules",
        "validatesCoreNamesAndRejectsOverlappingActions",
        "warningsRemainReviewableButNonBlocking",
    ):
        assert method in test
