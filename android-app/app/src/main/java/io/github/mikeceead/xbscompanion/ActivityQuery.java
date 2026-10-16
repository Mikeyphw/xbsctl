package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure-Java filtering model for the Room-backed Activity workbench. */
final class ActivityQuery {
    static final int MAX_QUERY_CHARS = 160;
    static final int MAX_SEARCH_FIELD_CHARS = 4096;

    enum Status {
        ALL("All"), ACTIVE("Active"), SUCCEEDED("Succeeded"), PROBLEMS("Problems"),
        TIMED_OUT("Timed out"), CANCELLED("Cancelled"), OUTPUT("Has output");
        final String label;
        Status(String label) { this.label = label; }
        Status next() { Status[] values = values(); return values[(ordinal() + 1) % values.length]; }
    }

    enum Window {
        ALL("Any time", Long.MAX_VALUE), DAY("Past 24 hours", 24L * 60L * 60L * 1000L),
        WEEK("Past 7 days", 7L * 24L * 60L * 60L * 1000L),
        MONTH("Past 30 days", 30L * 24L * 60L * 60L * 1000L);
        final String label;
        final long ageMs;
        Window(String label, long ageMs) { this.label = label; this.ageMs = ageMs; }
        Window next() { Window[] values = values(); return values[(ordinal() + 1) % values.length]; }
    }

    static final String SOURCE_ALL = "all";
    static final String[] SOURCES = {SOURCE_ALL, "app", "notification", "tasker", "boot", "unknown"};

    final String text;
    final Status status;
    final Window window;
    final String source;
    final boolean pinnedOnly;

    ActivityQuery(String text, Status status, Window window, String source, boolean pinnedOnly) {
        this.text = normalize(text, MAX_QUERY_CHARS);
        this.status = status == null ? Status.ALL : status;
        this.window = window == null ? Window.ALL : window;
        String normalizedSource = normalize(source, 32);
        this.source = knownSource(normalizedSource) ? normalizedSource : SOURCE_ALL;
        this.pinnedOnly = pinnedOnly;
    }

    boolean matches(RunRecord record, long now) {
        if (record == null) return false;
        if (pinnedOnly && !record.pinned) return false;
        if (!SOURCE_ALL.equals(source) && !source.equals(normalize(record.source, 32))) return false;
        if (!matchesStatus(record)) return false;
        if (window != Window.ALL) {
            long timestamp = record.finishedAt != 0L ? record.finishedAt : record.startedAt;
            if (timestamp <= 0L || now < timestamp || now - timestamp > window.ageMs) return false;
        }
        return text.isEmpty() || haystack(record).contains(text);
    }

    List<RunRecord> apply(List<RunRecord> records, long now) {
        List<RunRecord> out = new ArrayList<>();
        if (records == null) return out;
        for (RunRecord record : records) if (matches(record, now)) out.add(record);
        return RunRetentionPolicy.sorted(out);
    }


    boolean matches(XbsEvent event, long now) {
        if (event == null || pinnedOnly || !SOURCE_ALL.equals(source)) return false;
        if (status != Status.ALL && status != Status.PROBLEMS) return false;
        if (status == Status.PROBLEMS && !event.isImportant()) return false;
        long timestamp = event.timestamp > 0L ? event.timestamp * 1000L : 0L;
        if (window != Window.ALL && (timestamp <= 0L || now < timestamp || now - timestamp > window.ageMs)) return false;
        if (text.isEmpty()) return true;
        String haystack = field(event.id) + '\n' + field(event.type) + '\n' + field(event.name)
            + '\n' + field(event.state) + '\n' + field(event.severity) + '\n' + field(event.message);
        return haystack.contains(text);
    }

    List<XbsEvent> applyEvents(List<XbsEvent> events, long now) {
        List<XbsEvent> out = new ArrayList<>();
        if (events == null) return out;
        for (XbsEvent event : events) if (matches(event, now)) out.add(event);
        return out;
    }

    String summary(int count) {
        return count + " shown · " + status.label + " · " + sourceLabel(source) + " · " + window.label
            + (pinnedOnly ? " · pinned only" : "");
    }

    static String nextSource(String current) {
        String normalized = normalize(current, 32);
        for (int i = 0; i < SOURCES.length; i++) {
            if (SOURCES[i].equals(normalized)) return SOURCES[(i + 1) % SOURCES.length];
        }
        return SOURCE_ALL;
    }

    static String sourceLabel(String source) {
        String value = normalize(source, 32);
        if (SOURCE_ALL.equals(value) || value.isEmpty()) return "All sources";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    static boolean isProblem(RunRecord record) {
        if (record == null) return false;
        String status = normalize(record.status, 64);
        return "failed".equals(status) || "timeout".equals(status) || "timed_out".equals(status)
            || "cancelled".equals(status) || "canceled".equals(status)
            || record.exitCode == 124 || record.exitCode == 130
            || hasText(record.error) || hasText(record.stderr);
    }

    private boolean matchesStatus(RunRecord record) {
        switch (status) {
            case ACTIVE: return RunFilter.isActive(record);
            case SUCCEEDED: return "ok".equals(record.status);
            case PROBLEMS: return isProblem(record);
            case TIMED_OUT: return record.exitCode == 124 || "timeout".equals(normalize(record.status, 64)) || "timed_out".equals(normalize(record.status, 64));
            case CANCELLED: return record.exitCode == 130 || "cancelled".equals(normalize(record.status, 64)) || "canceled".equals(normalize(record.status, 64));
            case OUTPUT: return hasText(record.stdout) || hasText(record.stderr) || hasText(record.error);
            case ALL:
            default: return true;
        }
    }

    private static String haystack(RunRecord record) {
        return field(record.runId) + '\n' + field(record.module) + '\n' + field(record.actionId) + '\n'
            + field(record.title) + '\n' + field(record.status) + '\n' + field(record.command) + '\n'
            + field(record.source) + '\n' + field(record.mutationClass) + '\n' + field(record.message) + '\n'
            + field(record.stdout) + '\n' + field(record.stderr) + '\n' + field(record.error);
    }

    private static boolean knownSource(String value) {
        for (String candidate : SOURCES) if (candidate.equals(value)) return true;
        return false;
    }

    private static String field(String value) { return normalize(value, MAX_SEARCH_FIELD_CHARS); }
    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
    private static String normalize(String value) { return normalize(value, Integer.MAX_VALUE); }
    private static String normalize(String value, int max) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
