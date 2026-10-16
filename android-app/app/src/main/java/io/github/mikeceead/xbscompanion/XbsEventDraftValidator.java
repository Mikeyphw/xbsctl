package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure event-editor validation shared by the UI and unit tests. */
final class XbsEventDraftValidator {
    private static final int MAX_MANAGED_LIST_LINES = 1000;
    private static final int MAX_MANAGED_LIST_CHARS = 65536;

    static final String FIELD_NAME = "name";
    static final String FIELD_ALLOWLIST = "allowlist";
    static final String FIELD_DENYLIST = "denylist";
    static final String FIELD_PROCESS_RULES = "process_rules";
    static final String FIELD_HANDLE_CORES = "handle_cores";
    static final String FIELD_DISABLE_CORES = "disable_cores";
    static final String FIELD_BEHAVIOR = "behavior";

    enum Severity { ERROR, WARNING }

    static final class Issue {
        final Severity severity;
        final String field;
        final int line;
        final String message;

        Issue(Severity severity, String field, int line, String message) {
            this.severity = severity;
            this.field = field == null ? "" : field;
            this.line = Math.max(0, line);
            this.message = message == null ? "" : message;
        }

        boolean isError() { return severity == Severity.ERROR; }

        String displayText() {
            return line > 0 ? "Line " + line + ": " + message : message;
        }
    }

    static final class Draft {
        final String targetName;
        final String originalName;
        final boolean template;
        final boolean clone;
        final boolean builtInOriginal;
        final Set<String> existingNames;
        final String allowlist;
        final String denylist;
        final String processRules;
        final boolean inheritAllowlist;
        final boolean inheritDenylist;
        final boolean inheritProcessRules;
        final String handleApps;
        final boolean handleProcessRules;
        final String handleCoresMode;
        final String handleCoresCustom;
        final String disableCoresMode;
        final String disableCoresCustom;
        final boolean quit;

        Draft(
                String targetName,
                String originalName,
                boolean template,
                boolean clone,
                boolean builtInOriginal,
                Set<String> existingNames,
                String allowlist,
                String denylist,
                String processRules,
                boolean inheritAllowlist,
                boolean inheritDenylist,
                boolean inheritProcessRules,
                String handleApps,
                boolean handleProcessRules,
                String handleCoresMode,
                String handleCoresCustom,
                String disableCoresMode,
                String disableCoresCustom,
                boolean quit) {
            this.targetName = clean(targetName);
            this.originalName = clean(originalName);
            this.template = template;
            this.clone = clone;
            this.builtInOriginal = builtInOriginal;
            this.existingNames = existingNames == null
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(existingNames));
            this.allowlist = value(allowlist);
            this.denylist = value(denylist);
            this.processRules = value(processRules);
            this.inheritAllowlist = inheritAllowlist;
            this.inheritDenylist = inheritDenylist;
            this.inheritProcessRules = inheritProcessRules;
            this.handleApps = clean(handleApps).toLowerCase(Locale.ROOT);
            this.handleProcessRules = handleProcessRules;
            this.handleCoresMode = clean(handleCoresMode).toLowerCase(Locale.ROOT);
            this.handleCoresCustom = value(handleCoresCustom);
            this.disableCoresMode = clean(disableCoresMode).toLowerCase(Locale.ROOT);
            this.disableCoresCustom = value(disableCoresCustom);
            this.quit = quit;
        }
    }

    static final class Result {
        private final List<Issue> issues;

        Result(List<Issue> issues) {
            this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        }

        List<Issue> issues() { return issues; }

        boolean hasErrors() {
            for (Issue issue : issues) if (issue.isError()) return true;
            return false;
        }

        boolean hasWarnings() {
            for (Issue issue : issues) if (!issue.isError()) return true;
            return false;
        }

        int errorCount() {
            int count = 0;
            for (Issue issue : issues) if (issue.isError()) count++;
            return count;
        }

        int warningCount() {
            int count = 0;
            for (Issue issue : issues) if (!issue.isError()) count++;
            return count;
        }

        String firstError(String field) {
            for (Issue issue : issues) {
                if (issue.isError() && issue.field.equals(field)) return issue.displayText();
            }
            return "";
        }

        String firstErrorField() {
            for (Issue issue : issues) if (issue.isError()) return issue.field;
            return "";
        }

        String warningText(int maximum) {
            StringBuilder out = new StringBuilder();
            int shown = 0;
            for (Issue issue : issues) {
                if (issue.isError()) continue;
                if (shown >= maximum) break;
                if (out.length() > 0) out.append('\n');
                out.append("• ").append(issue.displayText());
                shown++;
            }
            int remaining = warningCount() - shown;
            if (remaining > 0) out.append("\n• …and ").append(remaining).append(" more");
            return out.toString();
        }
    }

    private XbsEventDraftValidator() {}

    static Result validate(Draft draft) {
        List<Issue> issues = new ArrayList<>();
        validateName(draft, issues);

        ParsedLines allow = parsePackages(draft.allowlist, FIELD_ALLOWLIST, issues);
        ParsedLines deny = parsePackages(draft.denylist, FIELD_DENYLIST, issues);
        ParsedLines process = parseProcessRules(draft.processRules, issues);

        for (Map.Entry<String, Integer> entry : allow.firstLines.entrySet()) {
            if (deny.firstLines.containsKey(entry.getKey())) {
                issues.add(new Issue(
                        Severity.WARNING,
                        FIELD_ALLOWLIST,
                        entry.getValue(),
                        entry.getKey() + " is present in both allowlist and denylist; allowlist wins at runtime"));
            }
        }

        Set<String> handledCores = parseCores(
                draft.handleCoresMode,
                draft.handleCoresCustom,
                FIELD_HANDLE_CORES,
                false,
                issues);
        Set<String> disabledCores = parseCores(
                draft.disableCoresMode,
                draft.disableCoresCustom,
                FIELD_DISABLE_CORES,
                true,
                issues);
        for (String core : handledCores) {
            if (disabledCores.contains(core)) {
                issues.add(new Issue(
                        Severity.ERROR,
                        FIELD_DISABLE_CORES,
                        0,
                        core + " cannot be both governor-managed and disabled"));
            }
        }

        if (!draft.template && draft.handleProcessRules && process.firstLines.isEmpty()) {
            issues.add(new Issue(
                    Severity.ERROR,
                    FIELD_PROCESS_RULES,
                    0,
                    draft.inheritProcessRules
                            ? "Process handling is enabled, but the inherited process-rule list is empty"
                            : "Process handling is enabled, but no process rules are defined"));
        }
        if (!draft.template && !"false".equals(draft.handleApps)
                && allow.firstLines.isEmpty() && deny.firstLines.isEmpty()) {
            issues.add(new Issue(
                    Severity.WARNING,
                    FIELD_BEHAVIOR,
                    0,
                    "App handling has no event allowlist or denylist and will rely only on the global essentials list"));
        }
        if (!draft.template && draft.quit && !"boot".equals(draft.targetName)) {
            issues.add(new Issue(
                    Severity.WARNING,
                    FIELD_BEHAVIOR,
                    0,
                    "Quit-after-activation is normally intended for the boot event"));
        }
        return new Result(issues);
    }

    private static void validateName(Draft draft, List<Issue> issues) {
        if (draft.template) return;
        String name = draft.targetName;
        if (name.isEmpty()) {
            issues.add(new Issue(Severity.ERROR, FIELD_NAME, 0, "Event name is required"));
            return;
        }
        if (!name.matches("[A-Za-z0-9_.-]+")) {
            issues.add(new Issue(
                    Severity.ERROR,
                    FIELD_NAME,
                    0,
                    "Use only letters, numbers, dots, underscores, and hyphens"));
        }
        boolean creatingIdentity = draft.clone || draft.originalName.isEmpty();
        if (creatingIdentity && XbsConfigApi.isBuiltInEvent(name)) {
            issues.add(new Issue(Severity.ERROR, FIELD_NAME, 0, "Built-in event names are reserved"));
        }
        if (draft.builtInOriginal && !draft.clone && !name.equals(draft.originalName)) {
            issues.add(new Issue(Severity.ERROR, FIELD_NAME, 0, "Built-in event identity cannot be changed"));
        }
        if (draft.existingNames.contains(name) && (!name.equals(draft.originalName) || draft.clone)) {
            issues.add(new Issue(
                    Severity.WARNING,
                    FIELD_NAME,
                    0,
                    "An existing event named " + name + " will be replaced only after confirmation"));
        } else {
            for (String existing : draft.existingNames) {
                if (!existing.equals(name) && existing.equalsIgnoreCase(name)) {
                    issues.add(new Issue(
                            Severity.WARNING,
                            FIELD_NAME,
                            0,
                            "Event name differs from existing " + existing + " only by letter case"));
                    break;
                }
            }
        }
    }

    private static ParsedLines parsePackages(String text, String field, List<Issue> issues) {
        LinkedHashMap<String, Integer> first = new LinkedHashMap<>();
        String[] lines = normalizedLines(text, field, issues);
        for (int index = 0; index < lines.length; index++) {
            String item = lines[index].trim();
            int line = index + 1;
            if (item.isEmpty() || item.startsWith("#")) continue;
            if (!isAndroidPackageName(item)) {
                issues.add(new Issue(
                        Severity.ERROR,
                        field,
                        line,
                        "Invalid Android package name: " + item));
                continue;
            }
            Integer previous = first.putIfAbsent(item, line);
            if (previous != null) {
                issues.add(new Issue(
                        Severity.WARNING,
                        field,
                        line,
                        "Duplicate entry " + item + " (first declared on line " + previous + ")"));
            }
        }
        return new ParsedLines(first);
    }

    private static ParsedLines parseProcessRules(String text, List<Issue> issues) {
        LinkedHashMap<String, Integer> first = new LinkedHashMap<>();
        String[] lines = normalizedLines(text, FIELD_PROCESS_RULES, issues);
        for (int index = 0; index < lines.length; index++) {
            String item = lines[index].trim();
            int line = index + 1;
            if (item.isEmpty() || item.startsWith("#")) continue;
            String[] parts = item.split("\\s+");
            if (parts.length > 2) {
                issues.add(new Issue(
                        Severity.ERROR,
                        FIELD_PROCESS_RULES,
                        line,
                        "Use one process pattern and an optional nice value"));
                continue;
            }
            String process = parts[0];
            if (containsShellMeta(process)) {
                issues.add(new Issue(
                        Severity.ERROR,
                        FIELD_PROCESS_RULES,
                        line,
                        "Process pattern contains unsafe shell punctuation"));
                continue;
            }
            if (parts.length == 2) {
                try {
                    int nice = Integer.parseInt(parts[1]);
                    if (nice < -20 || nice > 19) {
                        issues.add(new Issue(
                                Severity.ERROR,
                                FIELD_PROCESS_RULES,
                                line,
                                "Linux nice value must be between -20 and 19"));
                    }
                } catch (NumberFormatException exception) {
                    issues.add(new Issue(
                            Severity.ERROR,
                            FIELD_PROCESS_RULES,
                            line,
                            "Nice value must be an integer between -20 and 19"));
                }
            }
            Integer previous = first.putIfAbsent(process, line);
            if (previous != null) {
                issues.add(new Issue(
                        Severity.WARNING,
                        FIELD_PROCESS_RULES,
                        line,
                        "Duplicate process pattern " + process + " (first declared on line " + previous + ")"));
            }
        }
        return new ParsedLines(first);
    }

    private static Set<String> parseCores(
            String mode,
            String custom,
            String field,
            boolean disabling,
            List<Issue> issues) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (!"custom".equals(mode)) return result;
        String cleaned = value(custom).trim();
        if (cleaned.isEmpty()) {
            issues.add(new Issue(
                    Severity.ERROR,
                    field,
                    0,
                    disabling ? "Enter at least one CPU core to disable" : "Enter at least one CPU core to manage"));
            return result;
        }
        String[] tokens = cleaned.split("[\\s,]+");
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (!token.matches("cpu[0-9]+")) {
                issues.add(new Issue(
                        Severity.ERROR,
                        field,
                        0,
                        "Invalid CPU core " + token + "; use names such as cpu0 or cpu7"));
                continue;
            }
            if (!result.add(token)) {
                issues.add(new Issue(Severity.WARNING, field, 0, "Duplicate CPU core " + token));
            }
        }
        if (disabling && result.contains("cpu0")) {
            issues.add(new Issue(
                    Severity.WARNING,
                    field,
                    0,
                    "Disabling cpu0 is commonly rejected by the kernel and may destabilize the device"));
        }
        return result;
    }

    private static boolean containsShellMeta(String value) {
        return !isSafeProcessPattern(value);
    }

    private static boolean isSafeProcessPattern(String value) {
        if (value == null || value.isEmpty() || value.startsWith("-")) return false;
        return value.matches("[A-Za-z0-9_.:-]+");
    }

    private static boolean isAndroidPackageName(String value) {
        if ("android".equals(value)) return true;
        String[] segments = value.split("\\.", -1);
        if (segments.length < 2) return false;
        for (String segment : segments) {
            if (segment.isEmpty()) return false;
            if (!isAsciiLetter(segment.charAt(0))) return false;
            for (int index = 1; index < segment.length(); index++) {
                char character = segment.charAt(index);
                if (!isAsciiLetterOrDigit(character) && character != '_') return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char character) {
        return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return isAsciiLetter(character) || (character >= '0' && character <= '9');
    }

    private static String[] normalizedLines(String value, String field, List<Issue> issues) {
        String text = value(value).replace("\r", "");
        if (text.length() > MAX_MANAGED_LIST_CHARS) {
            issues.add(new Issue(
                    Severity.ERROR,
                    field,
                    0,
                    "Managed list is too large; keep it under " + MAX_MANAGED_LIST_CHARS + " characters"));
        }
        String[] lines = text.split("\n", -1);
        if (lines.length > MAX_MANAGED_LIST_LINES) {
            issues.add(new Issue(
                    Severity.ERROR,
                    field,
                    0,
                    "Managed list has too many lines; keep it under " + MAX_MANAGED_LIST_LINES + " entries"));
        }
        return lines;
    }

    private static String clean(String value) { return value(value).trim(); }

    private static String value(String value) { return value == null ? "" : value; }

    private static final class ParsedLines {
        final LinkedHashMap<String, Integer> firstLines;

        ParsedLines(LinkedHashMap<String, Integer> firstLines) {
            this.firstLines = firstLines;
        }
    }
}
