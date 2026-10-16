from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "apps/xbs/android-app/app/src/main/java/io/github/mikeceead/xbscompanion"
TEST_JAVA = ROOT / "apps/xbs/android-app/app/src/test/java/io/github/mikeceead/xbscompanion"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_phase15_uses_android_application_id_shape_not_loose_segments():
    validator = read(JAVA / "XbsEventDraftValidator.java")
    test = read(TEST_JAVA / "XbsEventDraftValidatorTest.java")
    assert "isAndroidPackageName" in validator
    assert 'if (segments.length < 2) return false' in validator
    assert "isAsciiLetter(segment.charAt(0))" in validator
    assert "isAsciiLetterOrDigit(character)" in validator
    assert "Character.isLetter" not in validator
    assert '"android".equals(value)' in validator
    assert "rejectsAndroidPackageNamesOutsideApplicationIdRules" in test
    assert "rejectsUnicodePackageCharactersDespiteJavaLetterClassification" in test


def test_phase15_process_patterns_are_positive_allowlist_not_metacharacter_blacklist():
    validator = read(JAVA / "XbsEventDraftValidator.java")
    test = read(TEST_JAVA / "XbsEventDraftValidatorTest.java")
    assert "isSafeProcessPattern" in validator
    assert 'value.matches("[A-Za-z0-9_.:-]+")' in validator
    assert "containsShellMeta(process)" in validator
    assert "rejectsGlobLikeProcessPatternsBeforeRootWork" in test


def test_phase15_managed_lists_are_bounded_before_transactional_save():
    validator = read(JAVA / "XbsEventDraftValidator.java")
    test = read(TEST_JAVA / "XbsEventDraftValidatorTest.java")
    assert "MAX_MANAGED_LIST_LINES" in validator
    assert "MAX_MANAGED_LIST_CHARS" in validator
    assert "Managed list has too many lines" in validator
    assert "Managed list is too large" in validator
    assert "rejectsOversizedManagedListsBeforeSave" in test


def test_phase16_parser_bounds_scan_output_rows_and_fields():
    parser = read(JAVA / "ProcessSampleParser.java")
    test = read(TEST_JAVA / "ProcessSampleParserTest.java")
    for token in (
        "MAX_SCAN_OUTPUT_CHARS",
        "MAX_SCAN_LINES",
        "MAX_PROCESS_ROWS",
        "MAX_FIELD_CHARS",
        "hasOversizedField(fields)",
        "Process scan output exceeded the parser safety bound",
        "Process scan returned too many rows to parse safely",
        "Process scan returned too many process rows to parse safely",
    ):
        assert token in parser
    assert "rejectsOversizedOrAmbiguousProcRows" in test
    assert "rejectsOversizedScanOutputBeforeParsing" in test
    assert "rejectsUnsafeProcRowsEvenWhenAnotherRowIsValid" in test
    assert "oversized process field" in parser
    assert "ambiguous process row" in parser


def test_phase16_internal_scanner_still_uses_final_stat_boundary_and_real_cmdline():
    repo = read(JAVA / "ProcessRepository.java")
    assert "RESTAT=${STAT##*) }" in repo
    assert "START=${20:-}" in repo
    assert r"\\000\\011\\015\\012" in repo
