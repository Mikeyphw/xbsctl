package io.github.mikeceead.xbscompanion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded retention shared by Room persistence, migration, and emergency fallback. */
final class RunRetentionPolicy {
    static final int DEFAULT_RECORDS = 120;
    static final int MAX_RECORDS = 240;
    static final int MAX_OUTPUT_CHARS = 24000;

    private RunRetentionPolicy() {}

    static List<RunRecord> trim(List<RunRecord> records) { return trim(records, MAX_RECORDS); }

    static List<RunRecord> trim(List<RunRecord> records, int requestedLimit) {
        int limit = Math.max(30, Math.min(MAX_RECORDS, requestedLimit));
        List<RunRecord> sorted = sorted(records);
        if (sorted.size() <= limit) return sorted;
        List<RunRecord> kept = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        appendMatching(sorted, kept, ids, limit, 0);
        appendMatching(sorted, kept, ids, limit, 1);
        appendMatching(sorted, kept, ids, limit, 2);
        return sorted(kept);
    }

    static List<RunRecord> sorted(List<RunRecord> records) {
        List<RunRecord> copy = new ArrayList<>();
        if (records != null) copy.addAll(records);
        copy.sort((left, right) -> {
            boolean leftPinned = left != null && left.pinned;
            boolean rightPinned = right != null && right.pinned;
            if (leftPinned != rightPinned) return leftPinned ? -1 : 1;
            return Comparator.comparingLong(RunRetentionPolicy::sortKey).reversed().compare(left, right);
        });
        return copy;
    }

    private static void appendMatching(List<RunRecord> sorted, List<RunRecord> kept, Set<String> ids,
                                       int limit, int pass) {
        for (RunRecord record : sorted) {
            if (kept.size() >= limit) break;
            if (record == null) continue;
            boolean match = pass == 0 ? RunFilter.isActive(record) : pass == 1 ? record.pinned : true;
            String id = record.runId == null ? "" : record.runId;
            if (match && ids.add(id)) kept.add(record);
        }
    }

    private static long sortKey(RunRecord record) {
        if (record == null) return 0L;
        return record.finishedAt > 0 ? record.finishedAt : record.startedAt;
    }
}
