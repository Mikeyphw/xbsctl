package io.github.mikeceead.xbscompanion;

import android.content.Context;
import org.json.JSONObject;

/** Bounded cache for the last Debug Workbench evaluation. */
final class DebugHealthStore {
    private static final String CACHE_KEY = "debug_health_v1";
    private static final int MAX_CACHE_CHARS = 32 * 1024;

    private DebugHealthStore() {}

    static DebugHealthReport cached(Context context) {
        if (context == null) return DebugHealthReport.notEvaluated();
        String raw = XbsCache.peek(context.getApplicationContext(), CACHE_KEY);
        if (raw == null || raw.trim().isEmpty() || raw.length() > MAX_CACHE_CHARS) return DebugHealthReport.notEvaluated();
        try { return DebugHealthReport.fromJson(new JSONObject(raw)); }
        catch (Exception ignored) { return DebugHealthReport.notEvaluated(); }
    }

    static void save(Context context, DebugHealthReport report) {
        if (context == null || report == null) return;
        String encoded = report.toJson().toString();
        if (encoded.length() <= MAX_CACHE_CHARS) XbsCache.put(context.getApplicationContext(), CACHE_KEY, encoded);
    }

    static void clear(Context context) {
        if (context != null) XbsCache.invalidate(context.getApplicationContext(), CACHE_KEY);
    }
}
