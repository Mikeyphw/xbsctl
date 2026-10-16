package io.github.mikeceead.xbscompanion;

import android.content.Context;
import org.json.JSONObject;

/** Stores only bounded result evidence; it never runs or retries a repair. */
final class GuidedFixStore {
    private static final String CACHE_KEY = "guided_fix_result_v1";
    private GuidedFixStore() {}

    static void save(Context context, GuidedFixResult result) {
        if (context == null || result == null) return;
        XbsCache.put(context.getApplicationContext(), CACHE_KEY, result.toJson().toString());
    }

    static GuidedFixResult cached(Context context) {
        if (context == null) return GuidedFixResult.none();
        String raw = XbsCache.peek(context.getApplicationContext(), CACHE_KEY);
        if (raw == null || raw.trim().isEmpty()) return GuidedFixResult.none();
        try { return GuidedFixResult.fromJson(new JSONObject(raw)); }
        catch (Exception ignored) { return GuidedFixResult.none(); }
    }
}
