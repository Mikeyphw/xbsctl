package io.github.mikeceead.xbscompanion;

import android.content.Context;
import org.json.JSONObject;

final class RootDiagnosticsStore {
    private static final String CACHE_KEY = "root_diagnostics_v1";
    private RootDiagnosticsStore() {}

    static RootDiagnosticsReport cached(Context context) {
        String raw = XbsCache.peek(context, CACHE_KEY);
        if (raw == null || raw.trim().isEmpty()) return RootDiagnosticsReport.unchecked();
        try { return RootDiagnosticsReport.fromJson(new JSONObject(raw)); }
        catch (Exception ignored) { return RootDiagnosticsReport.unchecked(); }
    }

    static void save(Context context, RootDiagnosticsReport report) {
        XbsCache.put(context, CACHE_KEY, report.toJson().toString());
    }

    static void clear(Context context) { XbsCache.invalidate(context, CACHE_KEY); }
}
