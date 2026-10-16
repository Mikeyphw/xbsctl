package io.github.mikeceead.xbscompanion;

import android.content.Context;
import org.json.JSONObject;

/** Bounded cache for the last Debug Workbench evaluation. */
final class DebugHealthStore {
    private static final String CACHE_KEY = "debug_health_v1";
    private static final String GENERATION_KEY = "debug_health_generation_v1";
    private static final int MAX_CACHE_CHARS = 32 * 1024;

    private DebugHealthStore() {}

    static DebugHealthReport cached(Context context) {
        if (context == null) return DebugHealthReport.notEvaluated();
        long evaluatedGeneration = 0L;
        try { evaluatedGeneration = Long.parseLong(XbsCache.peek(context.getApplicationContext(), GENERATION_KEY)); } catch (Exception ignored) {}
        if (evaluatedGeneration != EvidenceStateCoordinator.generation(context)) return DebugHealthReport.notEvaluated();
        String raw = XbsCache.peek(context.getApplicationContext(), CACHE_KEY);
        if (raw == null || raw.trim().isEmpty() || raw.length() > MAX_CACHE_CHARS) return DebugHealthReport.notEvaluated();
        try { return DebugHealthReport.fromJson(new JSONObject(raw)); }
        catch (Exception ignored) { return DebugHealthReport.notEvaluated(); }
    }

    static void save(Context context, DebugHealthReport report) {
        if (context == null || report == null) return;
        String encoded = report.toJson().toString();
        if (encoded.length() <= MAX_CACHE_CHARS) {
            Context app = context.getApplicationContext();
            XbsCache.put(app, CACHE_KEY, encoded);
            XbsCache.put(app, GENERATION_KEY, Long.toString(EvidenceStateCoordinator.generation(app)));
        }
    }

    static void clear(Context context) {
        if (context != null) XbsCache.invalidate(context.getApplicationContext(), CACHE_KEY, GENERATION_KEY);
    }
}
