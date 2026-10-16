package io.github.mikeceead.xbscompanion;

import android.content.Context;
import org.json.JSONObject;

/** Bounded cache for live or explicit static-fallback compatibility evidence. */
final class XbsCompatibilityStore {
    private static final String CACHE_KEY = "xbs_compatibility_v1";
    private static final int MAX_CACHE_CHARS = XbsCompatibilityReport.MAX_RAW_CHARS;
    private XbsCompatibilityStore() {}

    static XbsCompatibilityReport cached(Context context) {
        String raw = XbsCache.peek(context, CACHE_KEY);
        if (raw == null || raw.trim().isEmpty()) {
            return XbsCompatibilityReport.fallback(System.currentTimeMillis(), "Live compatibility has not been checked");
        }
        if (raw.length() > MAX_CACHE_CHARS) {
            return XbsCompatibilityReport.fallback(System.currentTimeMillis(), "Compatibility cache exceeded bounded evidence limit");
        }
        try { return XbsCompatibilityReport.fromJson(new JSONObject(raw)); }
        catch (Exception ignored) {
            return XbsCompatibilityReport.fallback(System.currentTimeMillis(), "Compatibility cache could not be parsed");
        }
    }

    static void save(Context context, XbsCompatibilityReport report) {
        String encoded = report.toJson().toString();
        if (encoded.length() > MAX_CACHE_CHARS) {
            encoded = XbsCompatibilityReport.unavailable("Compatibility evidence exceeded bounded cache limit").toJson().toString();
        }
        XbsCache.put(context.getApplicationContext(), CACHE_KEY, encoded);
        EvidenceStateCoordinator.publish(context, "compatibility", report.capturedAt);
    }

    static void clear(Context context) { XbsCache.invalidate(context.getApplicationContext(), CACHE_KEY); }
}
