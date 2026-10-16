package io.github.mikeceead.xbscompanion;

import java.util.Collections;
import java.util.List;

/** Bounded plain-text export for explicitly selected Activity workbench records. */
final class ActivityExportFormatter {
    private static final int MAX_OUTPUT = 6000;
    private static final int MAX_EVENT_PAYLOAD = 1200;
    static final int MAX_RUN_RECORDS = 60;
    static final int MAX_EVENT_RECORDS = 120;
    static final int MAX_TOTAL_CHARS = 192_000;
    private ActivityExportFormatter() {}

    static String format(List<RunRecord> selected) {
        List<RunRecord> runs = selected == null ? Collections.emptyList() : RunRetentionPolicy.sorted(selected);
        StringBuilder out = new StringBuilder();
        out.append("XBS Activity export\n");
        int limit = Math.min(MAX_RUN_RECORDS, runs.size());
        out.append("records=").append(runs.size()).append(" exported=").append(limit);
        if (runs.size() > limit) out.append(" omitted=").append(runs.size() - limit);
        out.append('\n');
        int index = 0;
        for (RunRecord run : runs) {
            if (index >= limit || out.length() >= MAX_TOTAL_CHARS) break;
            if (run == null) continue;
            out.append("\n[Run ").append(++index).append("]\n");
            out.append("id=").append(line(run.runId, 128)).append('\n');
            out.append("pinned=").append(run.pinned).append(" status=").append(line(run.status, 48))
                .append(" exit=").append(run.exitCode).append('\n');
            out.append("title=").append(line(run.title, 240)).append('\n');
            out.append("source=").append(line(run.source, 64)).append(" mutation=")
                .append(line(run.mutationClass, 64)).append('\n');
            out.append("started_at=").append(run.startedAt).append(" finished_at=").append(run.finishedAt).append('\n');
            out.append("command=").append(line(run.command, 1200)).append('\n');
            out.append("message=").append(line(run.message, 600)).append('\n');
            append(out, "stdout", run.stdout);
            append(out, "stderr", run.stderr);
            append(out, "error", run.error);
        }
        return bound(out.toString());
    }

    static String formatEvents(List<XbsEvent> selected) {
        List<XbsEvent> events = selected == null ? Collections.emptyList() : selected;
        StringBuilder out = new StringBuilder();
        out.append("XBS Event timeline export\n");
        int limit = Math.min(MAX_EVENT_RECORDS, events.size());
        out.append("records=").append(events.size()).append(" exported=").append(limit);
        if (events.size() > limit) out.append(" omitted=").append(events.size() - limit);
        out.append('\n');
        int index = 0;
        for (XbsEvent event : events) {
            if (index >= limit || out.length() >= MAX_TOTAL_CHARS) break;
            if (event == null) continue;
            out.append("\n[Event ").append(++index).append("]\n");
            out.append("id=").append(line(event.id, 160)).append('\n');
            out.append("type=").append(line(event.type, 80)).append(" state=").append(line(event.state, 80))
                .append(" severity=").append(line(event.severity, 40)).append('\n');
            out.append("timestamp=").append(event.timestamp).append(" replayed=").append(event.replayed).append('\n');
            out.append("message=").append(line(event.message, 600)).append('\n');
            out.append("payload=").append(line(event.payloadJson, MAX_EVENT_PAYLOAD)).append('\n');
        }
        return bound(out.toString());
    }

    private static void append(StringBuilder out, String label, String value) {
        if (value == null || value.trim().isEmpty() || out.length() >= MAX_TOTAL_CHARS) return;
        out.append(label).append("=\n").append(tail(value, MAX_OUTPUT)).append('\n');
    }

    private static String line(String value, int max) {
        String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String tail(String value, int max) {
        String safe = value == null ? "" : value;
        return safe.length() <= max ? safe : "…\n" + safe.substring(safe.length() - max);
    }

    private static String bound(String value) {
        return value.length() <= MAX_TOTAL_CHARS ? value : value.substring(0, MAX_TOTAL_CHARS - 16) + "\n…truncated\n";
    }
}
