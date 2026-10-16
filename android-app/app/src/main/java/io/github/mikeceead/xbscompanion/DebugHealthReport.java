package io.github.mikeceead.xbscompanion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, bounded health snapshot for the read-only Debug Workbench. */
final class DebugHealthReport {
    static final int FORMAT_VERSION = 1;
    static final int PASS = 0;
    static final int WARNING = 1;
    static final int BLOCKER = 2;
    static final int UNKNOWN = 3;

    static final int EVIDENCE_CURRENT = 0;
    static final int EVIDENCE_STALE = 1;
    static final int EVIDENCE_MISSING = 2;

    static final int MAX_ITEMS = 24;
    static final int MAX_DETAIL_CHARS = 480;
    static final int MAX_SUPPORT_TEXT_CHARS = 16 * 1024;

    static final class Item {
        final String id;
        final String domain;
        final String title;
        final String detail;
        final String source;
        final String actionId;
        final int severity;
        final int evidenceState;
        final long capturedAt;

        Item(String id, String domain, String title, String detail, String source,
             String actionId, int severity, int evidenceState, long capturedAt) {
            this.id = clean(id);
            this.domain = clean(domain);
            this.title = clean(title);
            this.detail = bounded(detail, MAX_DETAIL_CHARS);
            this.source = clean(source);
            this.actionId = clean(actionId);
            this.severity = validSeverity(severity);
            this.evidenceState = validEvidence(evidenceState);
            this.capturedAt = Math.max(0L, capturedAt);
        }

        boolean passed() { return severity == PASS; }
        boolean warning() { return severity == WARNING; }
        boolean blocker() { return severity == BLOCKER; }
        boolean unknown() { return severity == UNKNOWN; }
        boolean actionable() { return !actionId.isEmpty(); }

        String severityLabel() {
            if (blocker()) return "blocker";
            if (warning()) return "warning";
            if (unknown()) return "unknown";
            return "pass";
        }

        String evidenceLabel() {
            if (evidenceState == EVIDENCE_MISSING) return "missing";
            if (evidenceState == EVIDENCE_STALE) return "stale";
            return "current";
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("domain", domain);
                json.put("title", title);
                json.put("detail", detail);
                json.put("source", source);
                json.put("actionId", actionId);
                json.put("severity", severity);
                json.put("evidenceState", evidenceState);
                json.put("capturedAt", capturedAt);
            } catch (Exception ignored) {}
            return json;
        }

        static Item fromJson(JSONObject json) {
            if (json == null) return null;
            return new Item(
                json.optString("id"),
                json.optString("domain"),
                json.optString("title"),
                json.optString("detail"),
                json.optString("source"),
                json.optString("actionId"),
                json.optInt("severity", UNKNOWN),
                json.optInt("evidenceState", EVIDENCE_MISSING),
                json.optLong("capturedAt", 0L));
        }
    }

    final long evaluatedAt;
    final List<Item> items;

    DebugHealthReport(long evaluatedAt, List<Item> items) {
        this.evaluatedAt = Math.max(0L, evaluatedAt);
        List<Item> copy = new ArrayList<>();
        if (items != null) {
            for (Item item : items) {
                if (item == null || item.id.isEmpty() || copy.size() >= MAX_ITEMS) continue;
                copy.add(item);
            }
        }
        this.items = Collections.unmodifiableList(copy);
    }

    static DebugHealthReport notEvaluated() {
        return new DebugHealthReport(0L, Collections.emptyList());
    }

    boolean evaluated() { return evaluatedAt > 0L && !items.isEmpty(); }
    int blockerCount() { return count(BLOCKER); }
    int warningCount() { return count(WARNING); }
    int unknownCount() { return count(UNKNOWN); }
    int passCount() { return count(PASS); }

    String state() {
        if (!evaluated()) return "NOT_EVALUATED";
        if (blockerCount() > 0) return "BLOCKED";
        if (warningCount() > 0) return "ATTENTION";
        if (unknownCount() > 0) return "INCOMPLETE";
        return "HEALTHY";
    }

    String headline() {
        if (!evaluated()) return "Debug health has not been evaluated";
        if (blockerCount() > 0) return "Debug health blockers detected";
        if (warningCount() > 0) return "Debug health needs attention";
        if (unknownCount() > 0) return "Debug health evidence is incomplete";
        return "Debug health checks are healthy";
    }

    String compactSummary() {
        return "state=" + state() + " blockers=" + blockerCount()
            + " warnings=" + warningCount() + " unknown=" + unknownCount()
            + " passed=" + passCount() + " checks=" + items.size();
    }

    String supportText() {
        StringBuilder out = new StringBuilder(4096);
        out.append("format=").append(FORMAT_VERSION)
            .append(" evaluated_at=").append(evaluatedAt).append('\n');
        out.append("summary=").append(compactSummary()).append('\n');
        for (Item item : items) {
            out.append("check.").append(oneLine(item.id))
                .append('=').append(item.severityLabel())
                .append(" evidence=").append(item.evidenceLabel())
                .append(" captured_at=").append(item.capturedAt)
                .append(" domain=").append(oneLine(item.domain))
                .append(" source=").append(oneLine(item.source))
                .append(" title=").append(oneLine(item.title))
                .append(" detail=").append(oneLine(item.detail));
            if (!item.actionId.isEmpty()) out.append(" action=").append(oneLine(item.actionId));
            out.append('\n');
        }
        String text = out.toString();
        return text.length() <= MAX_SUPPORT_TEXT_CHARS ? text : text.substring(0, MAX_SUPPORT_TEXT_CHARS - 1) + "…";
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        JSONArray rows = new JSONArray();
        for (Item item : items) rows.put(item.toJson());
        try {
            json.put("format", FORMAT_VERSION);
            json.put("evaluatedAt", evaluatedAt);
            json.put("items", rows);
        } catch (Exception ignored) {}
        return json;
    }

    static DebugHealthReport fromJson(JSONObject json) {
        if (json == null || json.optInt("format", 0) != FORMAT_VERSION) return notEvaluated();
        List<Item> rows = new ArrayList<>();
        JSONArray items = json.optJSONArray("items");
        if (items != null) {
            if (items.length() > MAX_ITEMS) return notEvaluated();
            for (int i = 0; i < items.length() && rows.size() < MAX_ITEMS; i++) {
                Item item = Item.fromJson(items.optJSONObject(i));
                if (item != null && !item.id.isEmpty()) rows.add(item);
            }
        }
        return new DebugHealthReport(json.optLong("evaluatedAt", 0L), rows);
    }

    private int count(int severity) {
        int count = 0;
        for (Item item : items) if (item.severity == severity) count++;
        return count;
    }

    private static int validSeverity(int value) {
        return value >= PASS && value <= UNKNOWN ? value : UNKNOWN;
    }

    private static int validEvidence(int value) {
        return value >= EVIDENCE_CURRENT && value <= EVIDENCE_MISSING
            ? value : EVIDENCE_MISSING;
    }

    private static String bounded(String value, int max) {
        String cleaned = clean(value);
        if (cleaned.length() <= max) return cleaned;
        return cleaned.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String clean(String value) {
        if (value == null) return "";
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim().replaceAll(" +", " ");
        return cleaned.length() <= 240 ? cleaned : cleaned.substring(0, 239) + "…";
    }
    private static String oneLine(String value) {
        return clean(value).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
            .replaceAll(" +", " ");
    }
}
