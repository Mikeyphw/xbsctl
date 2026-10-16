package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable release-readiness gate result built only from bounded cached evidence. */
final class ReleaseReadinessReport {
    static final int FORMAT_VERSION = 1;
    static final int MAX_ITEMS = 64;
    static final int MAX_FIELD_CHARS = 700;
    static final int PASS = 0;
    static final int WARNING = 1;
    static final int BLOCKER = 2;

    static final class Item {
        final String id;
        final String title;
        final String detail;
        final int severity;

        Item(String id, String title, String detail, int severity) {
            this.id = oneLine(id, MAX_FIELD_CHARS);
            this.title = oneLine(title, MAX_FIELD_CHARS);
            this.detail = oneLine(detail, MAX_FIELD_CHARS);
            this.severity = severity < PASS || severity > BLOCKER ? BLOCKER : severity;
        }

        boolean passed() { return severity == PASS; }
        boolean warning() { return severity == WARNING; }
        boolean blocker() { return severity == BLOCKER; }
        String severityLabel() { return blocker() ? "blocker" : warning() ? "warning" : "pass"; }
    }

    final long evaluatedAt;
    final String versionName;
    final long versionCode;
    final boolean debugBuild;
    final List<Item> items;

    ReleaseReadinessReport(long evaluatedAt, String versionName, long versionCode,
                           boolean debugBuild, List<Item> items) {
        this.evaluatedAt = Math.max(0L, evaluatedAt);
        this.versionName = clean(versionName);
        this.versionCode = Math.max(0L, versionCode);
        this.debugBuild = debugBuild;
        List<Item> copy = items == null ? Collections.emptyList() : items;
        this.items = Collections.unmodifiableList(new ArrayList<>(copy.subList(0, Math.min(copy.size(), MAX_ITEMS))));
    }

    int blockerCount() { return count(BLOCKER); }
    int warningCount() { return count(WARNING); }
    int passCount() { return count(PASS); }
    boolean releaseReady() { return blockerCount() == 0; }

    String state() {
        if (blockerCount() > 0) return "BLOCKED";
        if (warningCount() > 0) return "ATTENTION";
        return "READY";
    }

    String headline() {
        if (blockerCount() > 0) return "Release readiness blocked";
        if (warningCount() > 0) return "Release ready with attention items";
        return "Release readiness sealed";
    }

    String compactSummary() {
        return "state=" + state() + " blockers=" + blockerCount() + " warnings="
            + warningCount() + " passed=" + passCount() + " checks=" + items.size();
    }

    String supportText() {
        StringBuilder out = new StringBuilder(2048);
        out.append("format=").append(FORMAT_VERSION)
            .append(" evaluated_at=").append(evaluatedAt).append('\n');
        out.append("app_version=").append(oneLine(versionName, MAX_FIELD_CHARS))
            .append(" version_code=").append(versionCode)
            .append(" debug_build=").append(debugBuild).append('\n');
        out.append("summary=").append(compactSummary()).append('\n');
        for (Item item : items) {
            out.append("check.").append(oneLine(item.id, MAX_FIELD_CHARS))
                .append("=").append(item.severityLabel())
                .append(" title=").append(oneLine(item.title, MAX_FIELD_CHARS))
                .append(" detail=").append(oneLine(item.detail, MAX_FIELD_CHARS)).append('\n');
        }
        return out.toString();
    }

    private int count(int severity) {
        int count = 0;
        for (Item item : items) if (item.severity == severity) count++;
        return count;
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String oneLine(String value, int maxChars) {
        String clean = clean(value).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
            .replaceAll(" +", " ");
        int limit = Math.max(0, maxChars);
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
}
