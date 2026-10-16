package io.github.mikeceead.xbscompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

public final class XbsEventDraftValidatorTest {
    private static XbsEventDraftValidator.Draft draft(
            String name,
            String allow,
            String deny,
            String process,
            boolean handleProcess,
            String handleMode,
            String handleCustom,
            String disableMode,
            String disableCustom) {
        Set<String> existing = new LinkedHashSet<>(Arrays.asList("boot", "manual", "gaming"));
        return new XbsEventDraftValidator.Draft(
                name,
                "",
                false,
                false,
                false,
                existing,
                allow,
                deny,
                process,
                false,
                false,
                false,
                "nice",
                handleProcess,
                handleMode,
                handleCustom,
                disableMode,
                disableCustom,
                false);
    }

    @Test public void acceptsWellFormedEventDraft() {
        XbsEventDraftValidator.Result result = XbsEventDraftValidator.validate(draft(
                "travel_mode",
                "com.example.reader\nandroid\n",
                "com.example.game\n",
                "com.example.sync 10\n",
                true,
                "custom",
                "cpu0 cpu1",
                "custom",
                "cpu6 cpu7"));
        assertFalse(result.hasErrors());
        assertEquals(0, result.errorCount());
    }

    @Test public void rejectsReservedOrMalformedIdentity() {
        XbsEventDraftValidator.Result reserved = XbsEventDraftValidator.validate(draft(
                "boot", "", "", "", false, "false", "", "false", ""));
        assertTrue(reserved.hasErrors());
        assertFalse(reserved.firstError(XbsEventDraftValidator.FIELD_NAME).isEmpty());

        XbsEventDraftValidator.Result malformed = XbsEventDraftValidator.validate(draft(
                "bad event", "", "", "", false, "false", "", "false", ""));
        assertTrue(malformed.hasErrors());
    }

    @Test public void detectsPackageErrorsDuplicatesAndAllowDenyConflicts() {
        XbsEventDraftValidator.Result result = XbsEventDraftValidator.validate(draft(
                "study",
                "com.example.app\ncom.example.app\nbad package\n",
                "com.example.app\n",
                "",
                false,
                "false",
                "",
                "false",
                ""));
        assertTrue(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertFalse(result.firstError(XbsEventDraftValidator.FIELD_ALLOWLIST).isEmpty());
    }

    @Test public void validatesProcessRuleShapeSafetyAndNiceRange() {
        XbsEventDraftValidator.Result result = XbsEventDraftValidator.validate(draft(
                "study",
                "",
                "",
                "good.proc 10\nbad;proc 10\nother 25\nthree 1 columns\n",
                true,
                "false",
                "",
                "false",
                ""));
        assertTrue(result.hasErrors());
        assertFalse(result.firstError(XbsEventDraftValidator.FIELD_PROCESS_RULES).isEmpty());
    }

    @Test public void processHandlingRequiresRules() {
        XbsEventDraftValidator.Result result = XbsEventDraftValidator.validate(draft(
                "study", "", "", "", true, "false", "", "false", ""));
        assertTrue(result.hasErrors());
        assertTrue(result.firstError(XbsEventDraftValidator.FIELD_PROCESS_RULES).contains("no process rules"));
    }

    @Test public void validatesCoreNamesAndRejectsOverlappingActions() {
        XbsEventDraftValidator.Result malformed = XbsEventDraftValidator.validate(draft(
                "study", "", "", "", false, "custom", "0 cpu1", "false", ""));
        assertTrue(malformed.hasErrors());
        assertFalse(malformed.firstError(XbsEventDraftValidator.FIELD_HANDLE_CORES).isEmpty());

        XbsEventDraftValidator.Result overlap = XbsEventDraftValidator.validate(draft(
                "study", "", "", "", false, "custom", "cpu4 cpu5", "custom", "cpu5 cpu6"));
        assertTrue(overlap.hasErrors());
        assertTrue(overlap.firstError(XbsEventDraftValidator.FIELD_DISABLE_CORES).contains("both"));
    }



    @Test public void rejectsAndroidPackageNamesOutsideApplicationIdRules() {
        XbsEventDraftValidator.Result result = XbsEventDraftValidator.validate(draft(
                "study",
                "example\n1com.example\n_com.example\ncom.9bad\ncom.good_name.app\nandroid\n",
                "",
                "com.example.safe 10\n",
                true,
                "false",
                "",
                "false",
                ""));
        assertTrue(result.hasErrors());
        assertTrue(result.firstError(XbsEventDraftValidator.FIELD_ALLOWLIST).contains("Invalid Android package name"));
    }


    @Test public void rejectsUnicodePackageCharactersDespiteJavaLetterClassification() {
        XbsEventDraftValidator.Result result = XbsEventDraftValidator.validate(draft(
                "study",
                "com.exämple.app\n",
                "",
                "com.example.safe 10\n",
                true,
                "false",
                "",
                "false",
                ""));
        assertTrue(result.hasErrors());
        assertTrue(result.firstError(XbsEventDraftValidator.FIELD_ALLOWLIST).contains("Invalid Android package name"));
    }

    @Test public void rejectsGlobLikeProcessPatternsBeforeRootWork() {
        XbsEventDraftValidator.Result result = XbsEventDraftValidator.validate(draft(
                "study",
                "",
                "",
                "com.example.* 10\nzygote[0-9] 5\nvalid.process:worker -5\n",
                true,
                "false",
                "",
                "false",
                ""));
        assertTrue(result.hasErrors());
        assertTrue(result.firstError(XbsEventDraftValidator.FIELD_PROCESS_RULES).contains("unsafe shell punctuation"));
    }

    @Test public void rejectsOversizedManagedListsBeforeSave() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1002; i++) huge.append("com.example.app").append(i).append('\n');
        XbsEventDraftValidator.Result result = XbsEventDraftValidator.validate(draft(
                "study",
                huge.toString(),
                "",
                "",
                false,
                "false",
                "",
                "false",
                ""));
        assertTrue(result.hasErrors());
        assertTrue(result.firstError(XbsEventDraftValidator.FIELD_ALLOWLIST).contains("too many lines"));
    }

    @Test public void warningsRemainReviewableButNonBlocking() {
        XbsEventDraftValidator.Result result = XbsEventDraftValidator.validate(draft(
                "study",
                "com.example.app\n",
                "com.example.app\n",
                "",
                false,
                "false",
                "",
                "custom",
                "cpu0"));
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warningText(10).contains("both allowlist and denylist"));
        assertTrue(result.warningText(10).contains("cpu0"));
    }
}
